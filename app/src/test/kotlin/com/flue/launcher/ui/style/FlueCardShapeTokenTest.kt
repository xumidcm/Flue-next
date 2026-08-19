package com.flue.launcher.ui.style

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 卡片级圆角只许出自 [com.flue.launcher.ui.theme.LauncherStyle] 的形状令牌。
 *
 * ## 为什么要一条测试盯着
 *
 * 四档形状语言（chip < row < card < panel）在 `StyleSystem.kt` 里已经定好，注释也写了
 * 「禁止再写 `RoundedCornerShape(数字)` 字面量」—— 但那只是注释，随手写一个 24dp 编译
 * 照样过。攒下来的结果就是老师报的那条：**AW 风格里卡片圆角很多都不统一**。同一屏上
 * 弹窗 28、卡 26、面板 30、小组件卡 24、行 18 各写各的，单看每一处都"差不多"，叠在一起
 * 就明显参差；而且换风格时这些数字一个都不跟着变，AW 的 26dp 大圆角语言在写死的地方
 * 全部退化成 Material 的方角感。
 *
 * ## 判据只管卡片级
 *
 * 下限 14dp：再往下是徽标、角标、缩略图蒙版那类小装饰，它们本来就该按内容定，
 * 不属于形状语言。上限 64dp：`RoundedCornerShape(999.dp)` 是「整颗药丸」的惯用写法，
 * 表达的是**取满**而不是某一档尺寸，与 `chipShape`（`CircleShape`）等价，不算漂移。
 *
 * 允许清单里的几处都各有不能走令牌的理由，逐条写在下面。
 */
class FlueCardShapeTokenTest {

    /** 允许保留字面量的位置，以及不能走令牌的理由。 */
    private val allowed = mapOf(
        "ui/theme/StyleSystem.kt" to "令牌自己的定义处",
        // 崩溃页必须在主题系统本身可能已经挂掉的情况下渲染出来，所以它一个 Flue
        // 令牌都不读 —— 这是它能显示崩溃信息的前提。
        "CrashActivity.kt" to "崩溃页不依赖主题系统",
        // 专辑封面是「面板圆角 − 内边距」的同心推导（见调用点注释），不是一档形状。
        "ui/controlcenter/ControlCenterLayer.kt" to "同心圆角由面板半径推导",
        // 图标蒙版是另一套语言（圆 / squircle），由图标设置决定，与卡片无关。
        "ui/drawer/AppBubble.kt" to "图标蒙版形状",
        "ui/drawer/AppFolderOverlay.kt" to "图标蒙版形状",
        // 列表抽屉刻意把 M3 也按 AW 调色与行形状渲染（见 listPaletteStyle），
        // 走令牌会让 M3 的行圆角与它的调色脱节。
        "ui/drawer/ListDrawerScreen.kt" to "列表抽屉刻意统一按 AW 行形状渲染",
    )

    @Test
    fun `card sized corner radii come from the style tokens`() {
        val root = sequenceOf(File("src/main"), File("app/src/main"), File("../app/src/main"))
            .firstOrNull { it.isDirectory }
            ?: error("找不到 src/main —— 工作目录是 ${File(".").absolutePath}")

        val literal = Regex("""RoundedCornerShape\(\s*(\d+)\s*\.dp""")
        val violations = mutableListOf<String>()

        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val relative = file.path.replace('\\', '/').substringAfter("src/main/")
                if (allowed.keys.any { relative.endsWith(it) }) return@forEach
                file.readText().lineSequence().forEachIndexed { index, line ->
                    literal.findAll(line).forEach { match ->
                        val radius = match.groupValues[1].toInt()
                        if (radius in CARD_RADIUS_THRESHOLD_DP..CARD_RADIUS_CEILING_DP) {
                            violations += "$relative:${index + 1}  ${match.value})"
                        }
                    }
                }
            }

        assertEquals(
            "以下位置写死了卡片级圆角，请改用 LauncherTheme.style 的 " +
                "chipShape / rowShape / compactShape / cardShape / panelShape：\n" +
                violations.joinToString("\n") { "  $it" },
            emptyList<String>(),
            violations,
        )
    }

    private companion object {
        /** 14dp 以下算小装饰（徽标、角标、缩略图蒙版），不属于形状语言。 */
        const val CARD_RADIUS_THRESHOLD_DP = 14

        /** 超过这个数就不是「某一档圆角」而是「取满」的惯用写法（`999.dp` 药丸）。 */
        const val CARD_RADIUS_CEILING_DP = 64
    }
}
