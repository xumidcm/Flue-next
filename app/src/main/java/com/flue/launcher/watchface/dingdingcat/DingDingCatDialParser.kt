package com.flue.launcher.watchface.dingdingcat

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object DingDingCatDialParser {
    fun parse(rootDir: File): DingDingCatDial {
        val dialFile = File(rootDir, "dial.json")
        require(dialFile.isFile) { "缺少 dial.json" }
        val json = JSONObject(dialFile.readText(Charsets.UTF_8))
        val rawId = json.optString("id").trim()
        val id = rawId.ifBlank { rootDir.name }
        val title = json.optString("title").trim().ifBlank { id }
        val width = json.optInt("width", 480).takeIf { it > 0 } ?: 480
        val height = json.optInt("height", 480).takeIf { it > 0 } ?: 480
        val drawPoints = json.optJSONArray("drawpoints").toDrawPoints()
        return DingDingCatDial(
            id = id,
            title = title,
            width = width,
            height = height,
            rootDir = rootDir,
            drawPoints = drawPoints
        )
    }

    private fun JSONArray?.toDrawPoints(): List<DingDingCatDrawPoint> {
        if (this == null) return emptyList()
        val points = buildList {
            for (index in 0 until length()) {
                val obj = optJSONObject(index) ?: continue
                add(obj.toDrawPoint(index))
            }
        }
        val hasExplicitOrder = points.any { it.drawOrder != 0 }
        return if (hasExplicitOrder) points.sortedBy { it.drawOrder } else points
    }

    private fun JSONObject.toDrawPoint(index: Int): DingDingCatDrawPoint {
        val fontObj = optJSONObject("font")
        val animObj = optJSONObject("animorObj")
        return DingDingCatDrawPoint(
            sourceIndex = index,
            pointType = optString("pointType").trim(),
            pieces = optJSONArray("pices").toStringList(),
            startX = optFloat("startX", 0f),
            startY = optFloat("startY", 0f),
            hasStartX = has("startX"),
            hasStartY = has("startY"),
            width = optFloat("width", 0f),
            height = optFloat("height", 0f),
            scale = optFloat("scale", 1f).takeIf { it > 0f } ?: 1f,
            splitWidth = optFloat("splitWidth", 0f),
            splitSpace = optFloat("splitSpace", 0f),
            alpha = optInt("alpha", -1),
            tint = optInt("tint", -1),
            color = optInt("color", 0),
            gravity = optInt("gravity", 0),
            text = optString("text"),
            drawOrder = optInt("drawOrder", index),
            durationMs = optInt("duaution", 66).coerceAtLeast(16),
            visible = optBoolean("visibility", true),
            netDrawpoint = optBoolean("netDrawpoint", false),
            aminEffectStr = optString("aminEffectStr").takeIf { it.isNotBlank() },
            animator = animObj?.let { obj ->
                DingDingCatAnimator(
                    totalDurationMs = obj.optString("aminorconstruction")
                        .substringBefore(',')
                        .toIntOrNull()
                        ?.takeIf { it > 0 }
                        ?: 0,
                    triggerType = obj.optInt("triggerType", 0),
                    startWithStatusPlay = obj.optBoolean("startWithStatusPlay", false),
                    endWithStatusGone = obj.optBoolean("endWithStatusGone", false),
                    animEndSaveData = obj.optBoolean("animEndSaveData", false)
                )
            },
            font = DingDingCatFont(
                color = fontObj?.optInt("color", -1) ?: -1,
                textSize = fontObj?.optFloat("textSize", 32f) ?: 32f,
                fontType = fontObj?.optString("fontType")?.takeIf { it.isNotBlank() }
            )
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun JSONObject.optFloat(name: String, fallback: Float): Float {
        return when (val value = opt(name)) {
            is Number -> value.toFloat()
            is String -> value.toFloatOrNull() ?: fallback
            else -> fallback
        }
    }
}
