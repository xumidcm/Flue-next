package com.flue.launcher.applist.nativeview

import android.graphics.Bitmap
import com.flue.launcher.applist.model.AppItemKey
import com.flue.launcher.applist.model.AppListEntry
import com.flue.launcher.applist.model.AppListSnapshot
import com.flue.launcher.applist.model.IconKey
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Low-frequency content submitted across the Compose/native View boundary.
 *
 * The map is structurally copied and exposed read-only. Bitmap values are only
 * retained by reference: this boundary never copies or recycles them.
 */
class NativeAppListContent(
    val snapshot: AppListSnapshot,
    iconsByKey: Map<IconKey, Bitmap>,
) {
    val iconsByKey: Map<IconKey, Bitmap> =
        Collections.unmodifiableMap(LinkedHashMap(iconsByKey))

    internal val rows: List<NativeAppListBoundRow<Bitmap>> =
        bindNativeAppListRows(snapshot, this.iconsByKey)
}

internal data class NativeAppListBoundRow<T>(
    val snapshotGeneration: Long,
    val stableKey: AppItemKey,
    val entry: AppListEntry,
    val icon: T?,
)

internal fun <T> bindNativeAppListRows(
    snapshot: AppListSnapshot,
    iconsByKey: Map<IconKey, T>,
): List<NativeAppListBoundRow<T>> = Collections.unmodifiableList(
    snapshot.entries.map { entry ->
        NativeAppListBoundRow(
            snapshotGeneration = snapshot.generation,
            stableKey = entry.key,
            entry = entry,
            icon = iconsByKey[entry.iconKey],
        )
    },
)
