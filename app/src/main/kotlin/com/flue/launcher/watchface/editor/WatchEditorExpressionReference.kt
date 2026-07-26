package com.flue.launcher.watchface.editor

/**
 * Curated Watchmaker tag quick-reference for the editor's properties panel.
 * The tag list and semantics follow the upstream `WM_TAGS.md` specification;
 * only the most common ~28 tags are surfaced on the watch screen.
 */
data class WatchEditorExpressionEntry(
    val tag: String,
    val description: String,
)

data class WatchEditorExpressionGroup(
    val title: String,
    val entries: List<WatchEditorExpressionEntry>,
)

val WATCH_EDITOR_EXPRESSION_GROUPS: List<WatchEditorExpressionGroup> = listOf(
    WatchEditorExpressionGroup(
        title = "时间",
        entries = listOf(
            WatchEditorExpressionEntry("{dh}", "时（12 小时制）"),
            WatchEditorExpressionEntry("{dhz}", "时（12 小时制，补零）"),
            WatchEditorExpressionEntry("{dh23}", "时（24 小时制）"),
            WatchEditorExpressionEntry("{dh23z}", "时（24 小时制，补零）"),
            WatchEditorExpressionEntry("{dm}", "分"),
            WatchEditorExpressionEntry("{dmz}", "分（补零）"),
            WatchEditorExpressionEntry("{ds}", "秒"),
            WatchEditorExpressionEntry("{dsz}", "秒（补零）"),
            WatchEditorExpressionEntry("{da}", "上午 / 下午（AM/PM）"),
        ),
    ),
    WatchEditorExpressionGroup(
        title = "指针角度",
        entries = listOf(
            WatchEditorExpressionEntry("{drh}", "时针角度（随分钟平滑）"),
            WatchEditorExpressionEntry("{drm}", "分针角度（平滑）"),
            WatchEditorExpressionEntry("{drs}", "秒针角度（每秒 6°）"),
            WatchEditorExpressionEntry("{drss}", "秒针角度（亚秒平滑）"),
        ),
    ),
    WatchEditorExpressionGroup(
        title = "日期",
        entries = listOf(
            WatchEditorExpressionEntry("{dd}", "日（1–31）"),
            WatchEditorExpressionEntry("{ddz}", "日（补零）"),
            WatchEditorExpressionEntry("{dn}", "月（1–12）"),
            WatchEditorExpressionEntry("{dnn}", "月（补零）"),
            WatchEditorExpressionEntry("{dnnn}", "月份英文缩写（Jan–Dec）"),
            WatchEditorExpressionEntry("{dyy}", "年（4 位）"),
            WatchEditorExpressionEntry("{dy}", "年（末 2 位）"),
            WatchEditorExpressionEntry("{ddw}", "星期英文缩写（Mon–Sun）"),
            WatchEditorExpressionEntry("{ddww}", "星期英文全称"),
        ),
    ),
    WatchEditorExpressionGroup(
        title = "电量",
        entries = listOf(
            WatchEditorExpressionEntry("{bl}", "手表电量（0–100）"),
            WatchEditorExpressionEntry("{blp}", "手表电量（带 % 号）"),
            WatchEditorExpressionEntry("{bc}", "充电状态"),
            WatchEditorExpressionEntry("{br}", "电量旋转角（3.6°/％）"),
        ),
    ),
    WatchEditorExpressionGroup(
        title = "健康",
        entries = listOf(
            WatchEditorExpressionEntry("{shr}", "当前心率（BPM）"),
            WatchEditorExpressionEntry("{ssc}", "今日步数"),
            WatchEditorExpressionEntry("{sscp}", "步数目标完成度（%）"),
            WatchEditorExpressionEntry("{scal}", "消耗卡路里（kCal）"),
        ),
    ),
)

/**
 * Inserts [tag] into [text] at the cursor, replacing the current selection.
 * Returns the new text and cursor position (after the inserted tag). Offsets
 * are clamped so a stale selection can never throw.
 */
fun insertWatchEditorExpression(
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
    tag: String,
): Pair<String, Int> {
    val start = selectionStart.coerceIn(0, text.length)
    val end = selectionEnd.coerceIn(0, text.length)
    val from = minOf(start, end)
    val to = maxOf(start, end)
    val result = text.substring(0, from) + tag + text.substring(to)
    return result to (from + tag.length)
}
