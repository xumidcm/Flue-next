package com.flue.launcher.launchsession

import com.flue.launcher.applist.model.NormalizedPoint
import com.flue.launcher.ui.navigation.ScreenState

data class ExternalLaunchRequest(
    val targetKey: String,
    val expectedPackage: String?,
    val sourceState: ScreenState,
    val origin: NormalizedPoint,
) {
    init {
        require(targetKey.isNotBlank()) {
            "External launch target key must not be blank"
        }
        require(expectedPackage == null || expectedPackage.isNotBlank()) {
            "Expected package must be null or non-blank"
        }
    }
}

data class ForegroundPackageEvidence(
    val packageName: String,
    val observedAtElapsedMs: Long,
    val sequence: Long,
) {
    init {
        require(packageName.isNotBlank()) {
            "Foreground package evidence must contain a package name"
        }
        require(observedAtElapsedMs >= 0L) {
            "Foreground package evidence time must not be negative"
        }
        require(sequence >= 0L) {
            "Foreground package evidence sequence must not be negative"
        }
    }
}

data class ExternalLaunchSession(
    val id: Long,
    val targetKey: String,
    val expectedPackage: String?,
    val sourceState: ScreenState,
    val origin: NormalizedPoint,
    val startedAtElapsedMs: Long,
    val dispatchedAtElapsedMs: Long? = null,
    val lastExternalEvidence: ForegroundPackageEvidence? = null,
    val homeIntentPending: Boolean = false,
    val pausedAfterDispatch: Boolean = false,
)

sealed interface ExternalLaunchState {
    data object Idle : ExternalLaunchState

    data class MaskEntering(
        val session: ExternalLaunchSession,
    ) : ExternalLaunchState

    data class LaunchDispatched(
        val session: ExternalLaunchSession,
    ) : ExternalLaunchState

    data class Backgrounded(
        val session: ExternalLaunchSession,
    ) : ExternalLaunchState

    data class Returning(
        val session: ExternalLaunchSession,
        val kind: ReturnKind,
    ) : ExternalLaunchState

    data class Failed(
        val session: ExternalLaunchSession,
        val reason: FailureReason,
    ) : ExternalLaunchState

    data class Cancelled(
        val session: ExternalLaunchSession,
        val reason: CancelReason,
    ) : ExternalLaunchState

    data class Invalidated(
        val session: ExternalLaunchSession?,
        val reason: InvalidReason,
    ) : ExternalLaunchState
}

enum class ReturnKind {
    TargetReturn,
    ForeignReturn,
    UnverifiedReturn,
    HomeReentry,
}

enum class FailureReason {
    DispatchFailed,
}

enum class CancelReason {
    UserBack,
    HomeBeforeDispatch,
    ActivityLeftBeforeDispatch,
}

enum class InvalidReason {
    NoConfirmedBackground,
    ProcessRestore,
}

val ExternalLaunchState.usesLaunchOrigin: Boolean
    get() = when (this) {
        is ExternalLaunchState.MaskEntering,
        is ExternalLaunchState.LaunchDispatched,
        is ExternalLaunchState.Backgrounded,
        -> true

        // UnverifiedReturn means "we did launch from this icon and we were fully
        // backgrounded, we just could not get accessibility evidence". That is
        // not the same as "some other app came back", so reusing the launch
        // origin is safe. Only ForeignReturn / HomeReentry need a neutral pivot.
        // Requiring TargetReturn here meant every device without the
        // accessibility service granted lost its return animation entirely and
        // the icon simply vanished.
        is ExternalLaunchState.Returning ->
            kind == ReturnKind.TargetReturn || kind == ReturnKind.UnverifiedReturn

        ExternalLaunchState.Idle,
        is ExternalLaunchState.Failed,
        is ExternalLaunchState.Cancelled,
        is ExternalLaunchState.Invalidated,
        -> false
    }

val ExternalLaunchState.retainsLaunchIcon: Boolean
    get() = usesLaunchOrigin

class ExternalLaunchSessionTracker(
    private val elapsedRealtime: () -> Long,
) {

    @Volatile
    var state: ExternalLaunchState = ExternalLaunchState.Idle
        private set

    private var nextSessionId = 1L

    @Synchronized
    fun begin(request: ExternalLaunchRequest): Long {
        val id = nextSessionId++
        state = ExternalLaunchState.MaskEntering(
            ExternalLaunchSession(
                id = id,
                targetKey = request.targetKey,
                expectedPackage = request.expectedPackage,
                sourceState = request.sourceState,
                origin = request.origin,
                startedAtElapsedMs = elapsedRealtime(),
            ),
        )
        return id
    }

    @Synchronized
    fun onDispatchResult(id: Long, success: Boolean) {
        val entering = state as? ExternalLaunchState.MaskEntering ?: return
        if (entering.session.id != id) return

        state = if (success) {
            ExternalLaunchState.LaunchDispatched(
                entering.session.copy(dispatchedAtElapsedMs = elapsedRealtime()),
            )
        } else {
            ExternalLaunchState.Failed(
                session = entering.session,
                reason = FailureReason.DispatchFailed,
            )
        }
    }

    @Synchronized
    fun onPaused() {
        state = when (val current = state) {
            is ExternalLaunchState.MaskEntering -> ExternalLaunchState.Cancelled(
                session = current.session,
                reason = CancelReason.ActivityLeftBeforeDispatch,
            )

            is ExternalLaunchState.LaunchDispatched -> {
                if (current.session.pausedAfterDispatch) {
                    current
                } else {
                    current.copy(
                        session = current.session.copy(pausedAfterDispatch = true),
                    )
                }
            }

            else -> current
        }
    }

    @Synchronized
    fun onStopped() {
        state = when (val current = state) {
            is ExternalLaunchState.MaskEntering -> ExternalLaunchState.Cancelled(
                session = current.session,
                reason = CancelReason.ActivityLeftBeforeDispatch,
            )

            is ExternalLaunchState.LaunchDispatched -> ExternalLaunchState.Backgrounded(
                session = current.session,
            )

            else -> current
        }
    }

    @Synchronized
    fun recordEvidence(evidence: ForegroundPackageEvidence) {
        state = when (val current = state) {
            is ExternalLaunchState.LaunchDispatched -> current.withEvidence(evidence)
            is ExternalLaunchState.Backgrounded -> current.withEvidence(evidence)
            else -> current
        }
    }

    @Synchronized
    fun onHomeIntent() {
        state = when (val current = state) {
            is ExternalLaunchState.MaskEntering -> ExternalLaunchState.Cancelled(
                session = current.session,
                reason = CancelReason.HomeBeforeDispatch,
            )

            is ExternalLaunchState.LaunchDispatched -> current.copy(
                session = current.session.copy(homeIntentPending = true),
            )

            is ExternalLaunchState.Backgrounded -> current.copy(
                session = current.session.copy(homeIntentPending = true),
            )

            is ExternalLaunchState.Returning -> {
                if (current.kind == ReturnKind.HomeReentry) {
                    current
                } else {
                    current.copy(kind = ReturnKind.HomeReentry)
                }
            }

            is ExternalLaunchState.Failed -> ExternalLaunchState.Returning(
                session = current.session,
                kind = ReturnKind.HomeReentry,
            )

            is ExternalLaunchState.Cancelled -> ExternalLaunchState.Returning(
                session = current.session,
                kind = ReturnKind.HomeReentry,
            )

            is ExternalLaunchState.Invalidated -> current.session?.let { session ->
                ExternalLaunchState.Returning(
                    session = session,
                    kind = ReturnKind.HomeReentry,
                )
            } ?: current

            else -> current
        }
    }

    @Synchronized
    fun onResumed() {
        state = when (val current = state) {
            is ExternalLaunchState.LaunchDispatched -> {
                if (current.session.homeIntentPending) {
                    ExternalLaunchState.Returning(
                        session = current.session,
                        kind = ReturnKind.HomeReentry,
                    )
                } else if (current.session.pausedAfterDispatch) {
                    ExternalLaunchState.Invalidated(
                        session = current.session,
                        reason = InvalidReason.NoConfirmedBackground,
                    )
                } else {
                    current
                }
            }

            is ExternalLaunchState.Backgrounded -> ExternalLaunchState.Returning(
                session = current.session,
                kind = classifyReturn(current.session),
            )

            else -> current
        }
    }

    @Synchronized
    fun onBackgroundConfirmationTimedOut(id: Long) {
        val current = state as? ExternalLaunchState.LaunchDispatched ?: return
        if (current.session.id != id) return
        state = ExternalLaunchState.Invalidated(
            session = current.session,
            reason = InvalidReason.NoConfirmedBackground,
        )
    }

    @Synchronized
    fun cancel(id: Long, reason: CancelReason) {
        val session = when (val current = state) {
            is ExternalLaunchState.MaskEntering -> current.session
            is ExternalLaunchState.LaunchDispatched -> current.session
            is ExternalLaunchState.Backgrounded -> current.session
            else -> return
        }
        if (session.id != id) return

        state = ExternalLaunchState.Cancelled(
            session = session,
            reason = reason,
        )
    }

    @Synchronized
    fun invalidateProcessRestore() {
        val current = state
        if (
            current is ExternalLaunchState.Invalidated &&
            current.reason == InvalidReason.ProcessRestore
        ) {
            return
        }

        state = ExternalLaunchState.Invalidated(
            session = current.sessionOrNull(),
            reason = InvalidReason.ProcessRestore,
        )
    }

    @Synchronized
    fun acknowledge(id: Long) {
        val terminalSession = when (val current = state) {
            is ExternalLaunchState.Returning -> current.session
            is ExternalLaunchState.Failed -> current.session
            is ExternalLaunchState.Cancelled -> current.session
            is ExternalLaunchState.Invalidated -> current.session
            else -> null
        }
        if (terminalSession?.id == id) {
            state = ExternalLaunchState.Idle
        }
    }

    private fun ExternalLaunchState.LaunchDispatched.withEvidence(
        evidence: ForegroundPackageEvidence,
    ): ExternalLaunchState.LaunchDispatched {
        if (!session.accepts(evidence)) return this
        return copy(session = session.copy(lastExternalEvidence = evidence))
    }

    private fun ExternalLaunchState.Backgrounded.withEvidence(
        evidence: ForegroundPackageEvidence,
    ): ExternalLaunchState.Backgrounded {
        if (!session.accepts(evidence)) return this
        return copy(session = session.copy(lastExternalEvidence = evidence))
    }

    private fun ExternalLaunchSession.accepts(
        evidence: ForegroundPackageEvidence,
    ): Boolean {
        val dispatchTime = dispatchedAtElapsedMs ?: return false
        if (evidence.observedAtElapsedMs <= dispatchTime) return false
        val previous = lastExternalEvidence
        return previous == null || evidence.sequence > previous.sequence
    }

    private fun classifyReturn(session: ExternalLaunchSession): ReturnKind {
        if (session.homeIntentPending) return ReturnKind.HomeReentry
        val expectedPackage = session.expectedPackage
            ?: return ReturnKind.UnverifiedReturn
        val evidence = session.lastExternalEvidence
            ?: return ReturnKind.UnverifiedReturn
        return if (evidence.packageName == expectedPackage) {
            ReturnKind.TargetReturn
        } else {
            ReturnKind.ForeignReturn
        }
    }

    private fun ExternalLaunchState.sessionOrNull(): ExternalLaunchSession? = when (this) {
        ExternalLaunchState.Idle -> null
        is ExternalLaunchState.MaskEntering -> session
        is ExternalLaunchState.LaunchDispatched -> session
        is ExternalLaunchState.Backgrounded -> session
        is ExternalLaunchState.Returning -> session
        is ExternalLaunchState.Failed -> session
        is ExternalLaunchState.Cancelled -> session
        is ExternalLaunchState.Invalidated -> session
    }
}
