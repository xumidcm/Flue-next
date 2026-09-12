package com.flue.launcher.ui.prompt

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptForegroundMotionTest {
    @Test
    fun `full screen title body and actions share one reveal clock`() {
        val motion = promptForegroundMotion(reduceMotion = false)

        assertEquals(PromptRevealClock.SHARED_FOREGROUND, motion.titleClock)
        assertEquals(motion.titleClock, motion.bodyClock)
        assertEquals(motion.titleClock, motion.actionClock)
        assertTrue(motion.blurWholeForeground)
    }

    @Test
    fun `interactive surfaces use press scale and underdamped release`() {
        val motion = promptForegroundMotion(reduceMotion = false)

        assertTrue(motion.pressedScale < 1f)
        assertTrue(motion.releaseDampingRatio < 1f)
        assertTrue(motion.edgeRubberBand)
    }

    @Test
    fun `reduced motion disables elastic deformation`() {
        val motion = promptForegroundMotion(reduceMotion = true)

        assertEquals(1f, motion.pressedScale, 0f)
        assertFalse(motion.edgeRubberBand)
        assertFalse(motion.blurWholeForeground)
    }

    @Test
    fun `overlay host and dialog buttons consume the shared motion policy`() {
        val root = sequenceOf(File("src/main/kotlin"), File("app/src/main/kotlin"), File("../app/src/main/kotlin"))
            .firstOrNull(File::isDirectory) ?: error("找不到 Android Kotlin 源码目录")
        val overlay = File(root, "com/flue/launcher/ui/style/FlueOverlayHost.kt").readText()
        val dialog = File(root, "com/flue/launcher/ui/style/FlueDialog.kt").readText()

        assertTrue(overlay.contains("promptForegroundMotion(reduceMotion)"))
        assertTrue(overlay.contains("LocalFluePromptMotion provides motion"))
        assertTrue(overlay.contains("LocalFlueOverlayTextEffect provides if (revealWholeContent) PromptTextEffect.FADE"))
        assertTrue(overlay.contains("if (entry.scrollableContent && motion.edgeRubberBand)"))
        assertTrue(overlay.contains("Modifier.flueElasticNestedScroll(fullScreenScrollState)"))
        assertTrue(dialog.contains("motion.pressedScale"))
        assertTrue(dialog.contains("motion.releaseDampingRatio"))
    }
}
