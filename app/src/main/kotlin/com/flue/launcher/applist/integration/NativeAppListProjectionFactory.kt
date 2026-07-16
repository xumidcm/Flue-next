package com.flue.launcher.applist.integration

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import com.flue.launcher.applist.model.AppItemKey
import com.flue.launcher.applist.model.AppItemType
import com.flue.launcher.applist.model.AppListEntry
import com.flue.launcher.applist.model.AppListSnapshot
import com.flue.launcher.applist.model.IconKey
import com.flue.launcher.applist.model.LaunchTarget
import com.flue.launcher.applist.nativeview.NativeAppListContent
import com.flue.launcher.data.model.AppInfo
import com.flue.launcher.data.model.iconForDisplay
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Stateful boundary between the legacy launcher repository model and the
 * native app-list model. One factory instance belongs to one list host.
 */
class NativeAppListProjectionFactory {
    private var generation = 0L
    private var iconRevision = 0L
    private var previousEntries: List<AppListEntry>? = null
    private var previousIcons: Map<IconKey, Bitmap>? = null
    private var previousApps: List<AppInfo>? = null
    private var previousProjection: NativeAppListProjection? = null

    fun project(
        apps: List<AppInfo>,
        useTwoTone: Boolean,
    ): NativeAppListProjection {
        val entries = apps.map(AppInfo::toNativeAppListEntry)
        val icons = LinkedHashMap<IconKey, Bitmap>(apps.size)
        val appsByKey = LinkedHashMap<AppItemKey, AppInfo>(apps.size)
        apps.forEachIndexed { index, app ->
            val entry = entries[index]
            check(appsByKey.put(entry.key, app) == null) {
                "App list keys must be unique: ${entry.key.value}"
            }
            icons[entry.iconKey] = app.iconForDisplay(useTwoTone).asAndroidBitmap()
        }

        val entriesChanged = previousEntries != entries
        val iconsChanged = !sameBitmapIdentities(previousIcons, icons)
        val commandMetadataChanged = previousApps != apps
        previousProjection?.let { previous ->
            if (!entriesChanged && !iconsChanged && !commandMetadataChanged) return previous
        }

        if (previousEntries != null && entriesChanged) {
            check(generation < Long.MAX_VALUE) { "App list snapshot generation overflow" }
            generation += 1L
            iconRevision = 0L
        } else if (previousEntries != null && iconsChanged) {
            check(iconRevision < Long.MAX_VALUE) { "App list icon revision overflow" }
            iconRevision += 1L
        }

        val content = if (!entriesChanged && !iconsChanged) {
            checkNotNull(previousProjection).content
        } else {
            NativeAppListContent(
                snapshot = AppListSnapshot.create(generation, entries),
                iconsByKey = icons,
                iconRevision = iconRevision,
            )
        }
        val projection = NativeAppListProjection(
            content = content,
            appsByKey = Collections.unmodifiableMap(LinkedHashMap(appsByKey)),
        )
        previousEntries = content.snapshot.entries
        previousIcons = projection.content.iconsByKey
        previousApps = Collections.unmodifiableList(ArrayList(apps))
        previousProjection = projection
        return projection
    }

    private fun sameBitmapIdentities(
        previous: Map<IconKey, Bitmap>?,
        next: Map<IconKey, Bitmap>,
    ): Boolean {
        if (previous == null || previous.size != next.size) return false
        return next.all { (key, bitmap) -> previous[key] === bitmap }
    }
}

data class NativeAppListProjection(
    val content: NativeAppListContent,
    val appsByKey: Map<AppItemKey, AppInfo>,
)

/**
 * Keeps the stateful version owner aligned with the lifetime of one native
 * AndroidView host and avoids rebuilding O(n) projection data on unrelated
 * recompositions. [apps] must follow Compose's immutable-list convention.
 */
@Composable
fun rememberNativeAppListProjection(
    apps: List<AppInfo>,
    useTwoTone: Boolean,
): NativeAppListProjection {
    val factory = remember { NativeAppListProjectionFactory() }
    return remember(apps, useTwoTone, factory) {
        factory.project(apps, useTwoTone)
    }
}

fun AppInfo.toNativeAppListEntry(): AppListEntry = AppListEntry(
    key = AppItemKey(componentKey),
    label = label,
    type = when {
        isFolder -> AppItemType.FOLDER
        isAppListShortcut -> AppItemType.SHORTCUT
        else -> AppItemType.APP
    },
    launchTarget = when {
        isFolder -> null
        isAppListShortcut -> shortcutIntentUri?.let(LaunchTarget::IntentUri)
        else -> LaunchTarget.Component(
            packageName = packageName,
            className = activityName,
        )
    },
    iconKey = IconKey(componentKey),
    folderChildren = folderItemKeys.map(::AppItemKey),
)
