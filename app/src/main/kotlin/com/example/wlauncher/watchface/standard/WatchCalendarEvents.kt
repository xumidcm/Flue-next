package com.flue.launcher.watchface.standard

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat

/**
 * 表盘日程（`{cex}` / `{cNxx}` 一族）的读取。
 *
 * ## 权限
 *
 * `READ_CALENDAR` 是危险权限，**只在用户主动去设置里点开那一行时才申请**（与心率的
 * `BODY_SENSORS` 完全同一套做法，见 [hasHeartRatePermission]）。没授权时读取函数返回
 * null，快照里的 `calendarEvents` 就是 null，标签走 Unavailable 渲染 `--`。
 *
 * 注意 null 与空列表**不能混**：null 是「读不到」，空列表是「读到了、确实没安排」。
 * 混成一个的话「没授权」会被显示成「今天没有安排」。
 */
internal fun hasCalendarPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_CALENDAR,
    ) == PackageManager.PERMISSION_GRANTED

/** 上游文档说「Up to 10 upcoming events」，{cNxx} 的序号也只到 10。 */
internal const val MAX_WATCH_CALENDAR_EVENTS = 10

/**
 * 往后 [horizonMs] 之内、最近的最多 [MAX_WATCH_CALENDAR_EVENTS] 条日程。
 *
 * 用 `CalendarContract.Instances`（而不是 `Events`）是因为重复日程只有展开成实例
 * 才有具体的开始时刻 —— 表盘要显示的是「下一个会议几点」，不是「这个系列几点」。
 *
 * 这里做 IO，调用方必须节流（见 provider 里的 TTL）：日程查询比一次 `StatFs` 贵得多，
 * 每帧查一次会直接掉帧。
 */
internal fun readWatchCalendarEvents(
    context: Context,
    nowEpochMs: Long,
    horizonMs: Long = DEFAULT_CALENDAR_HORIZON_MS,
): List<WatchCalendarEvent>? {
    if (!hasCalendarPermission(context)) return null
    val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
    ContentUris.appendId(uriBuilder, nowEpochMs)
    ContentUris.appendId(uriBuilder, nowEpochMs + horizonMs)
    return runCatching {
        context.contentResolver.query(
            uriBuilder.build(),
            arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                CalendarContract.Instances.DISPLAY_COLOR,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
            ),
            // 已取消的实例不该出现在表盘上。
            "${CalendarContract.Instances.STATUS} != ${CalendarContract.Instances.STATUS_CANCELED}",
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext() && size < MAX_WATCH_CALENDAR_EVENTS) {
                    add(
                        WatchCalendarEvent(
                            id = cursor.getStringOrNull(0),
                            title = cursor.getStringOrNull(1),
                            location = cursor.getStringOrNull(2),
                            calendarName = cursor.getStringOrNull(3),
                            colorRgb = cursor.getIntOrNull(4)?.let { "%06x".format(it and 0xFFFFFF) },
                            allDay = cursor.getIntOrNull(5) == 1,
                            beginEpochMs = cursor.getLongOrNull(6),
                            endEpochMs = cursor.getLongOrNull(7),
                        ),
                    )
                }
            }
        }
    }.getOrNull()
}

/** 默认往后看 7 天。再远的日程在表盘上没有意义，查得也更慢。 */
private const val DEFAULT_CALENDAR_HORIZON_MS = 7L * 24 * 60 * 60 * 1_000

private fun android.database.Cursor.getStringOrNull(index: Int): String? =
    if (isNull(index)) null else runCatching { getString(index) }.getOrNull()

private fun android.database.Cursor.getIntOrNull(index: Int): Int? =
    if (isNull(index)) null else runCatching { getInt(index) }.getOrNull()

private fun android.database.Cursor.getLongOrNull(index: Int): Long? =
    if (isNull(index)) null else runCatching { getLong(index) }.getOrNull()
