package com.flue.launcher.watchface.store

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchStoreInstallTransactionTest {
    @Test
    fun `new install rolls back its files when catalog commit fails`() = runBlocking {
        val commitFailure = IllegalStateException("catalog is read-only")
        var rolledBack = false

        val failure = assertThrows(WatchStoreInstallTransactionException::class.java) {
            runBlocking {
                commitMarketInstallCatalog(
                    wasAlreadyInstalled = false,
                    commit = { throw commitFailure },
                    rollback = {
                        rolledBack = true
                        Result.success(Unit)
                    },
                )
            }
        }

        assertTrue(rolledBack)
        assertSame(commitFailure, failure.cause)
        assertTrue(failure.rollbackCompleted)
    }

    @Test
    fun `existing local install is never deleted when catalog commit fails`() = runBlocking {
        var rollbackCalled = false

        val failure = assertThrows(WatchStoreInstallTransactionException::class.java) {
            runBlocking {
                commitMarketInstallCatalog(
                    wasAlreadyInstalled = true,
                    commit = { error("catalog failed") },
                    rollback = {
                        rollbackCalled = true
                        Result.success(Unit)
                    },
                )
            }
        }

        assertFalse(rollbackCalled)
        assertFalse(failure.rollbackCompleted)
    }

    @Test
    fun `successful commit returns value without invoking rollback`() = runBlocking {
        var rollbackCalled = false

        val value = commitMarketInstallCatalog(
            wasAlreadyInstalled = false,
            commit = { "installed" },
            rollback = {
                rollbackCalled = true
                Result.success(Unit)
            },
        )

        assertEquals("installed", value)
        assertFalse(rollbackCalled)
    }

    @Test
    fun `cancellation never rolls back a possibly committed catalog`() {
        var rollbackCalled = false

        assertThrows(CancellationException::class.java) {
            runBlocking {
                commitMarketInstallCatalog(
                    wasAlreadyInstalled = false,
                    commit = { throw CancellationException("screen closed") },
                    rollback = {
                        rollbackCalled = true
                        Result.success(Unit)
                    },
                )
            }
        }

        assertFalse(rollbackCalled)
    }
}
