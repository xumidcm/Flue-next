package com.flue.launcher.applist.integration

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.flue.launcher.applist.model.AppItemKey
import com.flue.launcher.applist.model.AppItemType
import com.flue.launcher.applist.model.LaunchTarget
import com.flue.launcher.data.model.AppInfo
import com.flue.launcher.data.model.AppListItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeAppListProjectionFactoryTest {
    @Test
    fun projectsAppsShortcutsAndFoldersWithoutLosingStableIdentity() {
        val app = appInfo(label = "App", packageName = "pkg", activityName = "Main")
        val shortcut = appInfo(
            label = "Shortcut",
            packageName = "pkg",
            activityName = "shortcut-id",
            itemType = AppListItemType.SHORTCUT,
            shortcutIntentUri = "intent:#Intent;action=test;end",
        )
        val folder = appInfo(
            label = "Folder",
            packageName = "internal",
            activityName = "folder-id",
            itemType = AppListItemType.FOLDER,
            folderItemKeys = listOf(app.componentKey, shortcut.componentKey),
        )

        val projection = NativeAppListProjectionFactory().project(
            apps = listOf(app, shortcut, folder),
            useTwoTone = false,
        )

        assertEquals(0L, projection.content.snapshot.generation)
        assertEquals(0L, projection.content.iconRevision)
        assertEquals(listOf(app.componentKey, shortcut.componentKey, folder.componentKey), projection.appsByKey.keys.map(AppItemKey::value))
        assertSame(app, projection.appsByKey[AppItemKey(app.componentKey)])
        with(projection.content.snapshot.entries) {
            assertEquals(AppItemType.APP, this[0].type)
            assertEquals(LaunchTarget.Component("pkg", "Main"), this[0].launchTarget)
            assertEquals(AppItemType.SHORTCUT, this[1].type)
            assertEquals(LaunchTarget.IntentUri("intent:#Intent;action=test;end"), this[1].launchTarget)
            assertEquals(AppItemType.FOLDER, this[2].type)
            assertEquals(null, this[2].launchTarget)
            assertEquals(listOf(AppItemKey(app.componentKey), AppItemKey(shortcut.componentKey)), this[2].folderChildren)
        }
    }

    @Test
    fun unchangedProjectionReusesTheWholeBoundaryObject() {
        val app = appInfo()
        val factory = NativeAppListProjectionFactory()

        val first = factory.project(listOf(app), useTwoTone = false)
        val second = factory.project(listOf(app), useTwoTone = false)

        assertSame(first, second)
        assertSame(first.content, second.content)
    }

    @Test
    fun iconIdentityChangeAdvancesOnlyTheIconRevision() {
        val factory = NativeAppListProjectionFactory()
        val firstApp = appInfo()
        val first = factory.project(listOf(firstApp), useTwoTone = false)
        val replacementIcon = bitmap().asImageBitmap()
        val second = factory.project(
            apps = listOf(firstApp.copy(cachedIcon = replacementIcon)),
            useTwoTone = false,
        )

        assertEquals(first.content.snapshot.generation, second.content.snapshot.generation)
        assertEquals(first.content.iconRevision + 1L, second.content.iconRevision)
        assertSame(replacementIcon, second.appsByKey.values.single().cachedIcon)
    }

    @Test
    fun structuralChangeAdvancesSnapshotAndResetsIconRevision() {
        val factory = NativeAppListProjectionFactory()
        val app = appInfo(label = "Before")
        val initial = factory.project(listOf(app), useTwoTone = false)
        val iconOnly = factory.project(
            listOf(app.copy(cachedIcon = bitmap().asImageBitmap())),
            useTwoTone = false,
        )
        val structural = factory.project(
            listOf(app.copy(label = "After", cachedIcon = iconOnly.appsByKey.values.single().cachedIcon)),
            useTwoTone = false,
        )

        assertEquals(initial.content.snapshot.generation + 1L, structural.content.snapshot.generation)
        assertEquals(0L, structural.content.iconRevision)
        assertEquals("After", structural.content.snapshot.entries.single().label)
    }

    @Test
    fun commandMetadataChangeRefreshesLookupWithoutResubmittingNativeRows() {
        val factory = NativeAppListProjectionFactory()
        val shortcut = appInfo(
            itemType = AppListItemType.SHORTCUT,
            shortcutIntentUri = "intent:#Intent;action=test;end",
            pinnedShortcutId = "before",
        )
        val initial = factory.project(listOf(shortcut), useTwoTone = false)
        val updated = factory.project(
            listOf(shortcut.copy(pinnedShortcutId = "after")),
            useTwoTone = false,
        )

        assertSame(initial.content, updated.content)
        assertEquals("after", updated.appsByKey.values.single().pinnedShortcutId)
    }

    private fun appInfo(
        label: String = "Example",
        packageName: String = "com.example",
        activityName: String = ".MainActivity",
        itemType: AppListItemType = AppListItemType.APP,
        shortcutIntentUri: String? = null,
        pinnedShortcutId: String? = null,
        folderItemKeys: List<String> = emptyList(),
    ): AppInfo {
        val icon = bitmap().asImageBitmap()
        return AppInfo(
            label = label,
            packageName = packageName,
            activityName = activityName,
            cachedIcon = icon,
            cachedBlurredIcon = icon,
            itemType = itemType,
            shortcutIntentUri = shortcutIntentUri,
            pinnedShortcutId = pinnedShortcutId,
            folderItemKeys = folderItemKeys,
        )
    }

    private fun bitmap(): Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
}
