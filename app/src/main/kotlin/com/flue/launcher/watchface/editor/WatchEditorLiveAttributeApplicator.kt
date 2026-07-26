package com.flue.launcher.watchface.editor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Debounces live property edits so the preview follows typing without creating
 * one undo entry (and one disk flush) per keystroke.
 *
 * Threading contract: [submit], [flush], [consumePendingChanges] and
 * [cancelPending] must all be called from the same thread (the UI thread in
 * production). Only the actual session mutation hops to [ioContext];
 * [WatchEditorSession] itself is synchronized, so a straggling apply can never
 * corrupt the document.
 */
class WatchEditorLiveAttributeApplicator(
    private val session: WatchEditorSession,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) {
    private var pendingKey: WatchEditorLayerKey? = null
    private val pendingChanges = LinkedHashMap<String, String?>()
    private var scheduled: Job? = null
    private var inFlight: Job? = null

    val hasPendingEdits: Boolean
        get() = pendingChanges.isNotEmpty()

    /**
     * Coalesces [changes] into the pending batch and (re)schedules a debounced
     * apply. Edits targeting a different layer than the current batch drop the
     * stale batch first; the UI resets its drafts on selection change anyway.
     */
    fun submit(
        scope: CoroutineScope,
        key: WatchEditorLayerKey,
        changes: Map<String, String?>,
        onApplied: (WatchEditorSnapshot) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        if (pendingKey != null && pendingKey != key) pendingChanges.clear()
        pendingKey = key
        pendingChanges.putAll(changes)
        scheduled?.cancel()
        scheduled = scope.launch {
            delay(debounceMillis)
            scheduled = null
            val batch = consumePendingChanges() ?: return@launch
            // The apply runs as a sibling job: cancelling the (already fired)
            // debounce must not lose a batch that was consumed from pending.
            val work = scope.launch { applyBatch(batch, onApplied, onError) }
            inFlight = work
            work.join()
            if (inFlight === work) inFlight = null
        }
    }

    /**
     * Applies any pending batch immediately. Call before operations that must
     * observe the final attribute values (save, export, undo, transform end).
     */
    suspend fun flush(
        onApplied: (WatchEditorSnapshot) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        scheduled?.cancel()
        scheduled = null
        // Wait for a drain that already consumed its batch so session
        // mutations from this applicator stay strictly ordered.
        inFlight?.join()
        val batch = consumePendingChanges() ?: return
        applyBatch(batch, onApplied, onError)
    }

    /**
     * Synchronously removes and returns the pending batch without applying it.
     * Used by the canvas gesture path, which must mutate the session on the
     * caller thread before a transform transaction begins.
     */
    fun consumePendingChanges(): Pair<WatchEditorLayerKey, Map<String, String?>>? {
        scheduled?.cancel()
        scheduled = null
        val key = pendingKey ?: return null
        if (pendingChanges.isEmpty()) {
            pendingKey = null
            return null
        }
        val batch = LinkedHashMap(pendingChanges)
        pendingChanges.clear()
        pendingKey = null
        return key to batch
    }

    /** Drops the pending batch without applying it (session closed / replaced). */
    fun cancelPending() {
        scheduled?.cancel()
        scheduled = null
        pendingChanges.clear()
        pendingKey = null
    }

    private suspend fun applyBatch(
        batch: Pair<WatchEditorLayerKey, Map<String, String?>>,
        onApplied: (WatchEditorSnapshot) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val (key, changes) = batch
        val result = runCatching {
            withContext(ioContext) {
                session.updateLayerAttributes(key, changes)
                session.flushDraftForPreview()
                session.snapshot()
            }
        }
        result.fold(
            onSuccess = onApplied,
            onFailure = { error ->
                if (error is CancellationException) throw error
                onError(error)
            },
        )
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 150L
    }
}
