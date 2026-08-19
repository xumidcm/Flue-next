package com.flue.launcher.watchface.store

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchStoreUploadDecisionTest {
    @Test
    fun nonCompliantArchiveRequiresExplicitOverride() {
        assertEquals(
            WatchStoreUploadDecision.REVIEW,
            watchStoreUploadDecision(findings = 2, override = false),
        )
        assertEquals(
            WatchStoreUploadDecision.UPLOAD,
            watchStoreUploadDecision(findings = 2, override = true),
        )
    }

    @Test
    fun compliantArchiveUploadsWithoutOverride() {
        assertEquals(
            WatchStoreUploadDecision.UPLOAD,
            watchStoreUploadDecision(findings = 0, override = false),
        )
    }
}
