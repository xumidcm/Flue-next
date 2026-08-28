package com.flue.launcher.ui.store

import com.flue.launcher.watchface.store.WatchStoreInstallState
import com.flue.launcher.watchface.store.catalog.WatchFaceInstallSource
import com.flue.launcher.watchface.store.catalog.WatchFaceRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchStoreInteractionPolicyTest {
    @Test
    fun `signed out overflow menu replaces management actions with login`() {
        assertEquals(
            listOf(WatchStoreAccountMenuAction.LOGIN_OR_REGISTER),
            watchStoreAccountMenuActions(signedIn = false),
        )
        assertEquals(
            listOf(
                WatchStoreAccountMenuAction.MY_UPLOADS,
                WatchStoreAccountMenuAction.UPLOAD,
            ),
            watchStoreAccountMenuActions(signedIn = true),
        )
    }

    @Test
    fun `download count stays visible before and after installation`() {
        assertTrue(watchStoreInstallStateShowsDownloadCount(WatchStoreInstallState.NotInstalled))
        assertTrue(watchStoreInstallStateShowsDownloadCount(WatchStoreInstallState.Installed(record())))
        assertFalse(
            watchStoreInstallStateShowsDownloadCount(
                WatchStoreInstallState.Downloading(bytes = 1L, totalBytes = 10L, attempt = 1),
            ),
        )
        assertFalse(watchStoreInstallStateShowsDownloadCount(WatchStoreInstallState.Validating(attempt = 1)))
        assertFalse(watchStoreInstallStateShowsDownloadCount(WatchStoreInstallState.Installing(attempt = 1)))
        assertFalse(
            watchStoreInstallStateShowsDownloadCount(
                WatchStoreInstallState.Failed("failed", 1L, 2L, 1),
            ),
        )
    }

    private fun record() = WatchFaceRecord(
        logicalId = "logical",
        activeRevisionSha256 = "a".repeat(64),
        installedAtEpochMs = 1L,
        displayNameOverride = null,
        source = WatchFaceInstallSource.Market(storeId = "store", remoteId = "remote"),
        legacyIds = emptySet(),
    )
}
