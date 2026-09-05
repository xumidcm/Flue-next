package com.flue.launcher.watchface.store

import org.junit.Assert.assertEquals
import org.junit.Test

class TopCreatorsPagingTest {
    @Test
    fun `top creator pages request fixed ten item windows and deduplicate`() {
        assertEquals(10, nextTopCreatorsLimit(0))
        assertEquals(20, nextTopCreatorsLimit(10))
        assertEquals(30, nextTopCreatorsLimit(20))
        assertEquals(listOf("a", "b", "c"), appendTopCreatorUsernames(listOf("a", "b"), listOf("b", "c")))
    }
}
