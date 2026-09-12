package com.flue.launcher.watchface.selection

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchFaceCardSelectorContractTest {
    @Test
    fun `selector pages static previews and composes no extra neighbors`() {
        val selector = source("com/example/wlauncher/ui/home/WatchFaceCardSelector.kt")
        val preview = source("com/example/wlauncher/ui/settings/WatchFaceSettingCard.kt")

        assertTrue(selector.contains("HorizontalPager("))
        assertTrue(selector.contains("beyondViewportPageCount = 0"))
        assertTrue(selector.contains("key = { page -> items[page].stableId }"))
        assertTrue(selector.contains("WatchFaceStaticPreview("))
        assertTrue(preview.contains("playVideo = false"))
    }

    @Test
    fun `launcher owns cards and keeps the legacy list route`() {
        val launcher = source("com/example/wlauncher/LauncherActivity.kt")
        val selector = source("com/example/wlauncher/ui/home/WatchFaceCardSelector.kt")

        assertTrue(launcher.contains("WATCH_FACE_LONG_PRESS_TIMEOUT_MS = 450L"))
        assertTrue(launcher.contains("WatchFaceChooserDestination.CARDS"))
        assertTrue(launcher.contains("SETTINGS_DESTINATION_WATCH_FACES"))
        assertTrue(launcher.contains("WatchFaceCardSelector("))
        assertTrue(launcher.contains("watchFaceCardsVisible || promptOverlayActive"))
        assertFalse(selector.contains("PointerEventPass.Final"))
        assertFalse(launcher.contains("Intent(context, WatchStoreActivity::class.java).putExtra(\"auto_open\""))
    }

    @Test
    fun `cards match the direct preview hierarchy and expose navigation`() {
        val selector = source("com/example/wlauncher/ui/home/WatchFaceCardSelector.kt")
        val settingsCard = source("com/example/wlauncher/ui/settings/WatchFaceSettingCard.kt")

        assertTrue(selector.contains("FlueFullScreenOverlay("))
        assertTrue(selector.contains("MiuixIcons.Store"))
        assertTrue(selector.contains("watchface_cards_open_settings"))
        assertTrue(selector.contains("localizedWatchFaceAuthor"))
        assertFalse(selector.contains("watchface_cards_title"))
        assertFalse(selector.contains("style.cardColor.copy"))
        assertFalse(settingsCard.contains("MiuixIcons.Settings"))
        assertFalse(settingsCard.contains("onOpenSettings"))
    }

    @Test
    fun `selector sizes previews from constraints and transforms the complete card surface`() {
        val selector = source("com/example/wlauncher/ui/home/WatchFaceCardSelector.kt")

        assertTrue(selector.contains("watchFaceCardLayout("))
        assertTrue(selector.contains("WatchFaceCardVisualSurface("))
        assertFalse(selector.contains("230.dp"))
    }

    @Test
    fun `long titles marquee inside measured header width and remain readable with reduced motion`() {
        val selector = source("com/example/wlauncher/ui/home/WatchFaceCardSelector.kt")

        assertTrue(selector.contains("basicMarquee("))
        assertTrue(selector.contains("iterations = Int.MAX_VALUE"))
        assertTrue(selector.contains("ValueAnimator.areAnimatorsEnabled()"))
        assertTrue(selector.contains("overflow = TextOverflow.Ellipsis"))
        assertTrue(selector.contains("textAlign = TextAlign.Center"))
    }

    @Test
    fun `selector uses a finite safe viewport and distributes sections through its height`() {
        val selector = source("com/example/wlauncher/ui/home/WatchFaceCardSelector.kt")

        assertFalse(selector.contains("LocalConfiguration.current.screenHeightDp"))
        assertTrue(selector.contains("WindowInsets.safeDrawing.asPaddingValues()"))
        assertFalse(selector.contains(".height(viewportHeight)"))
        val host = source("com/flue/launcher/ui/style/FlueOverlayHost.kt")
        assertTrue(host.contains("if (entry.scrollableContent) Modifier.verticalScroll(fullScreenScrollState)"))
        assertTrue(selector.contains("verticalArrangement = Arrangement.SpaceEvenly"))
    }

    @Test
    fun `back affordance follows focused face luminance with an animated transition`() {
        val selector = source("com/example/wlauncher/ui/home/WatchFaceCardSelector.kt")

        assertTrue(selector.contains("rememberAppListSeedColor("))
        assertTrue(selector.contains("watchFaceCardUseDarkForeground("))
        assertTrue(selector.contains("animateColorAsState("))
        assertTrue(selector.contains("tint = backIconColor"))
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
