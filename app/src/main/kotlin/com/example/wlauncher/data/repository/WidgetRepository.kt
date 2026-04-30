package com.flue.launcher.data.repository

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import com.flue.launcher.data.model.WidgetInfo

class WidgetRepository(private val context: Context) {

    private val appWidgetManager: AppWidgetManager
        get() = AppWidgetManager.getInstance(context)

    fun getAllWidgets(): List<WidgetInfo> {
        return appWidgetManager.installedProviders
            .map { providerInfo -> providerInfo.toWidgetInfo() }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    fun getWidgetByKey(widgetKey: String, widgetId: Int = -1): WidgetInfo? {
        return findProviderInfo(widgetKey)?.toWidgetInfo(widgetId = widgetId)
    }

    fun findProviderInfo(widgetKey: String): AppWidgetProviderInfo? {
        return appWidgetManager.installedProviders.firstOrNull {
            "${it.provider.packageName}/${it.provider.className}" == widgetKey
        }
    }

    fun parseSlotValue(raw: String?): WidgetInfo? {
        if (raw.isNullOrBlank()) return null
        val separatorIndex = raw.indexOf(':')
        val widgetId = raw.substring(0, separatorIndex).toIntOrNull()
        val widgetKey = when {
            separatorIndex <= 0 -> raw
            else -> raw.substring(separatorIndex + 1)
        }
        return getWidgetByKey(widgetKey = widgetKey, widgetId = widgetId ?: -1)
    }

    fun extractWidgetId(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val separatorIndex = raw.indexOf(':')
        return if (separatorIndex <= 0) {
            null
        } else {
            raw.substring(0, separatorIndex).toIntOrNull()
        }
    }

    fun serializeSlotValue(widget: WidgetInfo): String {
        return "${widget.widgetId}:${widget.widgetKey}"
    }

    private fun AppWidgetProviderInfo.toWidgetInfo(widgetId: Int = -1): WidgetInfo {
        val label = runCatching { loadLabel(context.packageManager) }.getOrDefault(provider.shortClassName)
        return WidgetInfo(
            label = label,
            packageName = provider.packageName,
            providerClassName = provider.className,
            widgetId = widgetId,
            minHeightDp = minHeight.coerceAtLeast(0),
            configureClassName = configure?.className
        )
    }
}
