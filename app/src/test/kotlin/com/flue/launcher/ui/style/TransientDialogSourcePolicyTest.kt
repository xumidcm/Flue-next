package com.flue.launcher.ui.style

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class TransientDialogSourcePolicyTest {
    private val allowedFullscreenDialogFiles = setOf(
        "com/example/wlauncher/SettingsActivity.kt",
        "com/example/wlauncher/WatchStoreActivity.kt",
        "com/flue/launcher/ui/common/FlueImageCropOverlay.kt",
        "com/example/wlauncher/ui/common/InternalFileBrowser.kt",
        "com/flue/launcher/ui/store/WatchStorePageStack.kt",
    )

    @Test
    fun `transient surfaces do not use material alert dialog`() {
        val violations = kotlinSources().flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val code = line.substringBefore("//")
                if (
                    "androidx.compose.material3.AlertDialog" in code ||
                    Regex("""(?<!Flue)\bAlertDialog\s*\(""").containsMatchIn(code)
                ) {
                    "${relative(file)}:${index + 1}"
                } else {
                    null
                }
            }
        }.toList()
        assertEquals("瞬时提示必须进入 FlueOverlayHost：\n${violations.joinToString("\n")}", emptyList<String>(), violations)
    }

    @Test
    fun `platform dialog remains limited to enumerated full screen tasks`() {
        val violations = kotlinSources().flatMap { file ->
            val relative = relative(file)
            if (relative in allowedFullscreenDialogFiles) return@flatMap emptyList()
            file.readLines().mapIndexedNotNull { index, line ->
                val code = line.substringBefore("//")
                if (
                    !code.trimStart().startsWith("*") && (
                        "androidx.compose.ui.window.Dialog" in code ||
                            Regex("""(?<!Flue)\bDialog\s*\(""").containsMatchIn(code)
                        )
                ) {
                    "$relative:${index + 1}"
                } else {
                    null
                }
            }
        }.toList()
        assertEquals("原生 Dialog 仅允许用于列举的全屏任务页：\n${violations.joinToString("\n")}", emptyList<String>(), violations)
    }

    @Test
    fun `transient menus and bottom sheets use the overlay host`() {
        val forbidden = listOf(
            Regex("""\bDropdownMenu\s*\("""),
            Regex("""\bWindowListPopup\s*\("""),
            Regex("""\bModalBottomSheet\s*\("""),
            Regex("""\bBottomSheetScaffold\s*\("""),
        )
        val violations = kotlinSources().flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val code = line.substringBefore("//")
                if (forbidden.any { it.containsMatchIn(code) }) {
                    "${relative(file)}:${index + 1}"
                } else {
                    null
                }
            }
        }.toList()
        assertEquals("瞬时菜单与底部弹层必须进入 FlueOverlayHost：\n${violations.joinToString("\n")}", emptyList<String>(), violations)
    }

    @Test
    fun `overlay input blocker is a sibling and prompt exit is animated`() {
        val host = sourceRoots()
            .asSequence()
            .map { File(it, "com/flue/launcher/ui/style/FlueOverlayHost.kt") }
            .first(File::isFile)
            .readText()

        assertEquals(false, host.contains(".fillMaxSize()\n                        .pointerInput(chainToken)"))
        assertEquals(true, host.contains("FlueOverlayInputBlocker("))
        assertEquals(true, host.contains("targetValue = if (visible) 1f else 0f"))
        assertEquals(true, host.contains("policy.exitDurationMillis"))
    }

    @Test
    fun `backdrop and full screen content share the requested reveal motion`() {
        val host = sourceRoots()
            .asSequence()
            .map { File(it, "com/flue/launcher/ui/style/FlueOverlayHost.kt") }
            .first(File::isFile)
            .readText()
        val foreground = host.substringAfter("private fun FlueOverlayForeground(")
            .substringBefore("internal fun Modifier.fluePromptTextReveal")
        val dialog = sourceRoots()
            .asSequence()
            .map { File(it, "com/flue/launcher/ui/style/FlueDialog.kt") }
            .first(File::isFile)
            .readText()

        assertEquals(true, host.contains("maxBlurRadiusPx * backdropProgress.value"))
        assertEquals(true, host.contains("alpha = backdropProgress.value"))
        assertEquals(true, foreground.contains("revealWholeContent"))
        assertEquals(true, foreground.contains("renderEffect ="))
        assertEquals(true, host.contains("internal fun Modifier.fluePromptTextReveal"))
        assertEquals(true, dialog.contains("modifier = Modifier.fluePromptTextReveal()"))
    }

    private fun kotlinSources(): Sequence<File> = sourceRoots().asSequence()
        .flatMap { it.walkTopDown() }
        .filter { it.isFile && it.extension == "kt" }

    private fun sourceRoots(): List<File> {
        val moduleRoot = sequenceOf(File("."), File("app"), File("../app"))
            .firstOrNull { File(it, "src/main").isDirectory }
            ?: error("找不到 Android 源码目录")
        return listOf("kotlin", "java")
            .map { File(moduleRoot, "src/main/$it") }
            .filter(File::isDirectory)
    }

    private fun relative(file: File): String =
        file.path.replace('\\', '/').substringAfter("src/main/").substringAfter('/')
}
