package com.flue.launcher.ui.notification

/**
 * Keeps an application's notification group in its current slot when that
 * application replaces or appends a notification. Brand-new applications are
 * inserted ahead of the existing stack, newest first, so LazyColumn can move
 * the old rows out of the way before animating the new group into its slot.
 */
internal fun stabilizeNotificationGroupOrder(
    previous: List<NotificationGroupUi>,
    incoming: List<NotificationGroupUi>
): List<NotificationGroupUi> {
    if (incoming.size <= 1 || previous.isEmpty()) {
        return incoming.sortedByDescending(NotificationGroupUi::latestTime)
    }

    val incomingByPackage = incoming.associateBy(NotificationGroupUi::packageName)
    val previousPackages = previous.asSequence()
        .map(NotificationGroupUi::packageName)
        .toHashSet()

    val newlyArrived = incoming.asSequence()
        .filterNot { it.packageName in previousPackages }
        .sortedByDescending(NotificationGroupUi::latestTime)
        .toList()
    val retained = previous.asSequence()
        .mapNotNull { old -> incomingByPackage[old.packageName] }
        .toList()

    return newlyArrived + retained
}

internal fun alignNotificationPreviewOrder(
    orderedGroups: List<NotificationGroupUi>,
    previews: List<NotificationGroupUi>
): List<NotificationGroupUi> {
    val previewsByPackage = previews.associateBy(NotificationGroupUi::packageName)
    return orderedGroups.mapNotNull { group -> previewsByPackage[group.packageName] }
}
