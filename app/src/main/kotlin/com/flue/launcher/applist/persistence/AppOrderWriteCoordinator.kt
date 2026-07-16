package com.flue.launcher.applist.persistence

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes application-order writes while allowing newer UI commands to invalidate an
 * in-flight write before it reaches durable storage.
 */
class AppOrderWriteCoordinator {
    private val currentGeneration = AtomicLong(0L)
    private val writerMutex = Mutex()

    @ConsistentCopyVisibility
    data class Ticket internal constructor(
        internal val generation: Long,
        val order: List<String>,
    )

    fun issue(order: List<String>): Ticket = Ticket(
        generation = currentGeneration.incrementAndGet(),
        order = order.toList(),
    )

    suspend fun commit(
        ticket: Ticket,
        updateRepository: suspend (List<String>) -> Unit,
        persist: suspend (List<String>) -> Unit,
    ): Boolean {
        if (!ticket.isCurrent()) return false
        return writerMutex.withLock {
            if (!ticket.isCurrent()) return@withLock false

            updateRepository(ticket.order)
            if (!ticket.isCurrent()) return@withLock false

            persist(ticket.order)
            ticket.isCurrent()
        }
    }

    private fun Ticket.isCurrent(): Boolean = currentGeneration.get() == generation
}
