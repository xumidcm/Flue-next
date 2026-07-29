package com.flue.launcher.watchface.editor

import androidx.annotation.StringRes
import com.flue.launcher.R

/**
 * Curated Watchmaker tag quick-reference for the editor's properties panel.
 * The tag list and semantics follow the upstream `WM_TAGS.md` specification;
 * only the most common tags are surfaced on the watch screen.
 */
data class WatchEditorExpressionEntry(
    val tag: String,
    @StringRes val descriptionRes: Int,
)

data class WatchEditorExpressionGroup(
    @StringRes val titleRes: Int,
    val entries: List<WatchEditorExpressionEntry>,
)

val WATCH_EDITOR_EXPRESSION_GROUPS: List<WatchEditorExpressionGroup> = listOf(
    WatchEditorExpressionGroup(
        titleRes = R.string.watch_editor_expression_group_time,
        entries = listOf(
            WatchEditorExpressionEntry("{dh}", R.string.watch_editor_expression_dh),
            WatchEditorExpressionEntry("{dhz}", R.string.watch_editor_expression_dhz),
            WatchEditorExpressionEntry("{dh23}", R.string.watch_editor_expression_dh23),
            WatchEditorExpressionEntry("{dh23z}", R.string.watch_editor_expression_dh23z),
            WatchEditorExpressionEntry("{dm}", R.string.watch_editor_expression_dm),
            WatchEditorExpressionEntry("{dmz}", R.string.watch_editor_expression_dmz),
            WatchEditorExpressionEntry("{ds}", R.string.watch_editor_expression_ds),
            WatchEditorExpressionEntry("{dsz}", R.string.watch_editor_expression_dsz),
            WatchEditorExpressionEntry("{da}", R.string.watch_editor_expression_da),
        ),
    ),
    WatchEditorExpressionGroup(
        titleRes = R.string.watch_editor_expression_group_angles,
        entries = listOf(
            WatchEditorExpressionEntry("{drh}", R.string.watch_editor_expression_drh),
            WatchEditorExpressionEntry("{drm}", R.string.watch_editor_expression_drm),
            WatchEditorExpressionEntry("{drs}", R.string.watch_editor_expression_drs),
            WatchEditorExpressionEntry("{drss}", R.string.watch_editor_expression_drss),
        ),
    ),
    WatchEditorExpressionGroup(
        titleRes = R.string.watch_editor_expression_group_date,
        entries = listOf(
            WatchEditorExpressionEntry("{dd}", R.string.watch_editor_expression_dd),
            WatchEditorExpressionEntry("{ddz}", R.string.watch_editor_expression_ddz),
            WatchEditorExpressionEntry("{dn}", R.string.watch_editor_expression_dn),
            WatchEditorExpressionEntry("{dnn}", R.string.watch_editor_expression_dnn),
            WatchEditorExpressionEntry("{dnnn}", R.string.watch_editor_expression_dnnn),
            WatchEditorExpressionEntry("{dyy}", R.string.watch_editor_expression_dyy),
            WatchEditorExpressionEntry("{dy}", R.string.watch_editor_expression_dy),
            WatchEditorExpressionEntry("{ddw}", R.string.watch_editor_expression_ddw),
            WatchEditorExpressionEntry("{ddww}", R.string.watch_editor_expression_ddww),
        ),
    ),
    WatchEditorExpressionGroup(
        titleRes = R.string.watch_editor_expression_group_battery,
        entries = listOf(
            WatchEditorExpressionEntry("{bl}", R.string.watch_editor_expression_bl),
            WatchEditorExpressionEntry("{blp}", R.string.watch_editor_expression_blp),
            WatchEditorExpressionEntry("{bc}", R.string.watch_editor_expression_bc),
            WatchEditorExpressionEntry("{br}", R.string.watch_editor_expression_br),
        ),
    ),
    WatchEditorExpressionGroup(
        titleRes = R.string.watch_editor_expression_group_health,
        entries = listOf(
            WatchEditorExpressionEntry("{shr}", R.string.watch_editor_expression_shr),
            WatchEditorExpressionEntry("{ssc}", R.string.watch_editor_expression_ssc),
            WatchEditorExpressionEntry("{sscp}", R.string.watch_editor_expression_sscp),
            WatchEditorExpressionEntry("{scal}", R.string.watch_editor_expression_scal),
        ),
    ),
    WatchEditorExpressionGroup(
        titleRes = R.string.watch_editor_expression_group_weather,
        entries = listOf(
            WatchEditorExpressionEntry("{wm}", R.string.watch_editor_expression_wm),
            WatchEditorExpressionEntry("{wt}", R.string.watch_editor_expression_wt),
            WatchEditorExpressionEntry("{wtd}", R.string.watch_editor_expression_wtd),
            WatchEditorExpressionEntry("{wth}", R.string.watch_editor_expression_wth),
            WatchEditorExpressionEntry("{wthd}", R.string.watch_editor_expression_wthd),
            WatchEditorExpressionEntry("{wtl}", R.string.watch_editor_expression_wtl),
            WatchEditorExpressionEntry("{wtld}", R.string.watch_editor_expression_wtld),
            WatchEditorExpressionEntry("{wct}", R.string.watch_editor_expression_wct),
            WatchEditorExpressionEntry("{wci}", R.string.watch_editor_expression_wci),
            WatchEditorExpressionEntry("{wl}", R.string.watch_editor_expression_wl),
            WatchEditorExpressionEntry("{wcl}", R.string.watch_editor_expression_wcl),
            WatchEditorExpressionEntry("{wh}", R.string.watch_editor_expression_wh),
            WatchEditorExpressionEntry("{whp}", R.string.watch_editor_expression_whp),
            WatchEditorExpressionEntry("{wml}", R.string.watch_editor_expression_wml),
            WatchEditorExpressionEntry("{wisday}", R.string.watch_editor_expression_wisday),
            WatchEditorExpressionEntry("{wlu}", R.string.watch_editor_expression_wlu),
            WatchEditorExpressionEntry("{wf0dt}", R.string.watch_editor_expression_wf0dt),
            WatchEditorExpressionEntry("{wf1dth}", R.string.watch_editor_expression_wf1dth),
            WatchEditorExpressionEntry("{wf1ht}", R.string.watch_editor_expression_wf1ht),
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
