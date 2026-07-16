package com.flue.launcher.applist.persistence

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppOrderWriteCoordinatorTest {

    @Test
    fun olderTicketIsSkippedWhenANewerOrderAlreadyExists() = runBlocking {
        val coordinator = AppOrderWriteCoordinator()
        val oldTicket = coordinator.issue(listOf("a", "b"))
        coordinator.issue(listOf("b", "a"))
        val repositoryWrites = mutableListOf<List<String>>()
        val persistedWrites = mutableListOf<List<String>>()

        val committed = coordinator.commit(
            ticket = oldTicket,
            updateRepository = repositoryWrites::add,
            persist = persistedWrites::add,
        )

        assertFalse(committed)
        assertTrue(repositoryWrites.isEmpty())
        assertTrue(persistedWrites.isEmpty())
    }

    @Test
    fun ticketIsRecheckedBetweenRepositoryAndPersistentWrites() = runBlocking {
        val coordinator = AppOrderWriteCoordinator()
        val oldTicket = coordinator.issue(listOf("a", "b"))
        val repositoryWrites = mutableListOf<List<String>>()
        val persistedWrites = mutableListOf<List<String>>()

        val committed = coordinator.commit(
            ticket = oldTicket,
            updateRepository = { order ->
                repositoryWrites += order
                coordinator.issue(listOf("b", "a"))
            },
            persist = persistedWrites::add,
        )

        assertFalse(committed)
        assertEquals(listOf(listOf("a", "b")), repositoryWrites)
        assertTrue(persistedWrites.isEmpty())
    }

    @Test
    fun issuedTicketOwnsAnImmutableSnapshotOfTheOrder() = runBlocking {
        val coordinator = AppOrderWriteCoordinator()
        val mutableOrder = mutableListOf("a", "b")
        val ticket = coordinator.issue(mutableOrder)
        mutableOrder.reverse()
        val repositoryWrites = mutableListOf<List<String>>()
        val persistedWrites = mutableListOf<List<String>>()

        val committed = coordinator.commit(
            ticket = ticket,
            updateRepository = repositoryWrites::add,
            persist = persistedWrites::add,
        )

        assertTrue(committed)
        assertEquals(listOf(listOf("a", "b")), repositoryWrites)
        assertEquals(listOf(listOf("a", "b")), persistedWrites)
    }
}
