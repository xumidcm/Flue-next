package com.flue.launcher

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchFaceFeedbackUiContractTest {
    @Test
    fun `aod settings are a child page instead of an inline appearance card`() {
        val settings = source("com/example/wlauncher/SettingsActivity.kt")

        assertTrue(settings.contains("SettingsDestination.AOD"))
        assertTrue(settings.contains("navigateTo(SettingsDestination.AOD)"))
        assertTrue(settings.contains("SettingsDestination.AOD -> {"))
        assertTrue(settings.substringAfter("SettingsDestination.AOD -> {").contains("SettingsPageScaffold("))
        assertFalse(settings.contains("item(\"aod_settings\") {\n                AodSettingsSection()"))
    }

    @Test
    fun `editor exposes fill preview toolbar order icons and scale slider`() {
        val editor = source("com/example/wlauncher/WatchEditorActivity.kt")

        assertTrue(editor.contains("view.setCanvasSettings(WatchCanvasSettings.GLOBAL_DEFAULT)"))
        assertTrue(editor.contains("fill = true"))
        assertTrue(editor.contains("MiuixIcons.Share"))
        assertTrue(editor.contains("EditorSelectionScaleSlider("))
        assertTrue(editor.indexOf("IconButton(onClick = onBack") < editor.indexOf("IconButton(onClick = onToggleMultiSelect"))
    }

    @Test
    fun `launcher theme gives outlined inputs the larger row shape`() {
        val theme = source("com/example/wlauncher/ui/theme/Theme.kt")
        assertTrue(theme.contains("FlueMaterialShapes"))
        assertTrue(theme.contains("val rowShape = style.rowShape as CornerBasedShape"))
        assertTrue(theme.contains("extraSmall = rowShape"))
    }

    @Test
    fun `corner mask slider is not nested inside the dialog vertical scroller`() {
        val settings = source("com/example/wlauncher/SettingsActivity.kt")
        val dialog = settings.substringAfter("private fun OverlayMaskCornerRadiusDialog(")
            .substringBefore("private fun", missingDelimiterValue = settings)

        assertTrue(dialog.contains("scrollableContent = false"))
        assertTrue(dialog.contains("FlueSlider("))
    }

    @Test
    fun `app long press menu keeps its original centered card presentation`() {
        val popup = source("com/example/wlauncher/ui/drawer/ShortcutPopup.kt")

        assertTrue(popup.contains("val panelWidth = (maxWidth * 0.78f)"))
        assertTrue(popup.contains("label = \"overlay_scale\""))
        assertTrue(popup.contains("snapshotFlow { animAlpha }"))
        assertFalse(popup.contains("FlueDialog("))
    }

    @Test
    fun `new watch flow always creates format one without a version chooser`() {
        val editor = source("com/example/wlauncher/WatchEditorActivity.kt")
        val creation = editor.substringAfter("val blankFaceIsRound").substringBefore("DisposableEffect(session)")

        assertFalse(editor.contains("private fun WatchFormatChoicePage("))
        assertFalse(creation.contains("pendingFormatChoice"))
        assertTrue(creation.contains("formatVersion = WatchFormatVersion.V1"))
    }

    private fun source(relative: String): String {
        val root = sequenceOf(
            File("src/main/kotlin"),
            File("app/src/main/kotlin"),
            File("../app/src/main/kotlin"),
        ).firstOrNull(File::isDirectory) ?: error("找不到 Android Kotlin 源码目录")
        return File(root, relative).readText()
    }
}
