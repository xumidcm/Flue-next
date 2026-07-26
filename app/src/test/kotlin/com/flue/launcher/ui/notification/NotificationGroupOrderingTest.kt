package com.flue.launcher.ui.notification

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationGroupOrderingTest {
    @Test
    fun sameApplicationUpdateKeepsItsExistingSlot() {
        val previous = listOf(group("alpha", 30), group("beta", 20))
        val incoming = listOf(group("beta", 99), group("alpha", 30))

        assertEquals(
            listOf("alpha", "beta"),
            stabilizeNotificationGroupOrder(previous, incoming).map { it.packageName }
        )
    }

    @Test
    fun newApplicationsEnterBeforeExistingGroupsNewestFirst() {
        val previous = listOf(group("alpha", 30), group("beta", 20))
        val incoming = listOf(
            group("alpha", 30),
            group("newer", 110),
            group("older-new", 90),
            group("beta", 20)
        )

        assertEquals(
            listOf("newer", "older-new", "alpha", "beta"),
            stabilizeNotificationGroupOrder(previous, incoming).map { it.packageName }
        )
    }

    @Test
    fun removedGroupsDisappearWithoutReorderingSurvivors() {
        val previous = listOf(group("alpha", 30), group("beta", 20), group("gamma", 10))
        val incoming = listOf(group("gamma", 100), group("alpha", 30))

        assertEquals(
            listOf("alpha", "gamma"),
            stabilizeNotificationGroupOrder(previous, incoming).map { it.packageName }
        )
    }

    private fun group(packageName: String, latestTime: Long): NotificationGroupUi {
        return NotificationGroupUi(
            packageName = packageName,
            appLabel = packageName,
            headerTitle = packageName,
            icon = null,
            latestTime = latestTime,
            latestSummary = packageName,
            entries = emptyList(),
            visiblePreviewEntries = emptyList(),
            hiddenPreviewCount = 0,
            expanded = false
        )
    }
}
