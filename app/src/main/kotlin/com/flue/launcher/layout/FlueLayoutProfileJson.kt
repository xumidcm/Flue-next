package com.flue.launcher.layout

import com.flue.launcher.ui.navigation.LayoutMode
import com.flue.launcher.util.FlowCrescentDirection
import org.json.JSONArray
import org.json.JSONObject

/**
 * 布局档案的 JSON 编解码。签名与校验风格照
 * [com.flue.launcher.motion.FlueMotionProfileJson] 抄，导入导出 UI 才能整段复用。
 */
object FlueLayoutProfileJson {
    private const val MAX_PROFILE_COUNT = 64
    private const val MAX_DOCUMENT_LENGTH = 256 * 1024

    private const val MODE_HONEYCOMB = "honeycomb"
    private const val MODE_GRID = "grid"
    private const val MODE_LIST = "list"
    private const val MODE_FLOW = "flow"
    private const val MODE_RING = "ring"

    private const val CRESCENT_LEFT = "left"
    private const val CRESCENT_RIGHT = "right"

    fun encodeProfile(profile: FlueLayoutProfile): String =
        profile.requireValid().toJson().toString(2)

    fun decodeProfile(payload: String): FlueLayoutProfile {
        requireDocumentSize(payload)
        return parseProfile(JSONObject(payload)).sanitized().requireValid()
    }

    fun encodeLibrary(profiles: List<FlueLayoutProfile>): String {
        require(profiles.size <= MAX_PROFILE_COUNT) { "Too many layout profiles" }
        require(profiles.map { it.id }.toSet().size == profiles.size) {
            "Duplicate layout profile ids"
        }
        val root = JSONObject()
            .put("schemaVersion", FLUE_LAYOUT_SCHEMA_VERSION)
            .put(
                "profiles",
                JSONArray().apply {
                    profiles.forEach { put(it.requireValid().toJson()) }
                },
            )
        return root.toString(2)
    }

    fun decodeLibrary(payload: String): List<FlueLayoutProfile> {
        requireDocumentSize(payload)
        val root = JSONObject(payload)
        require(root.requireInt("schemaVersion") == FLUE_LAYOUT_SCHEMA_VERSION) {
            "Unsupported layout library schema"
        }
        val profilesJson = root.optJSONArray("profiles")
            ?: throw IllegalArgumentException("Layout library profiles are missing")
        require(profilesJson.length() <= MAX_PROFILE_COUNT) { "Too many layout profiles" }
        val profiles = buildList {
            repeat(profilesJson.length()) { index ->
                val value = profilesJson.optJSONObject(index)
                    ?: throw IllegalArgumentException("profiles[$index] must be an object")
                add(parseProfile(value).sanitized().requireValid())
            }
        }
        require(profiles.map { it.id }.toSet().size == profiles.size) {
            "Duplicate layout profile ids"
        }
        return profiles
    }

    private fun parseProfile(json: JSONObject): FlueLayoutProfile = FlueLayoutProfile(
        schemaVersion = json.requireInt("schemaVersion"),
        id = json.requireString("id"),
        name = json.requireString("name"),
        layoutMode = json.requireLayoutMode("layoutMode"),
        fisheyeEnabled = json.requireBoolean("fisheyeEnabled"),
        topRowExtraFisheyeEnabled = json.requireBoolean("topRowExtraFisheyeEnabled"),
        edgeSpacingCompressionEnabled = json.requireBoolean("edgeSpacingCompressionEnabled"),
        fisheyeRangeRows = json.requireInt("fisheyeRangeRows"),
        fisheyeStrengthPercent = json.requireInt("fisheyeStrengthPercent"),
        leftSafeInsetPercent = json.requireInt("leftSafeInsetPercent"),
        crescentDirection = json.requireCrescentDirection("crescentDirection"),
        crescentBulgePercent = json.requireInt("crescentBulgePercent"),
    )

    private fun FlueLayoutProfile.toJson(): JSONObject = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("id", id)
        .put("name", name)
        .put("layoutMode", layoutMode.wireName())
        .put("fisheyeEnabled", fisheyeEnabled)
        .put("topRowExtraFisheyeEnabled", topRowExtraFisheyeEnabled)
        .put("edgeSpacingCompressionEnabled", edgeSpacingCompressionEnabled)
        .put("fisheyeRangeRows", fisheyeRangeRows)
        .put("fisheyeStrengthPercent", fisheyeStrengthPercent)
        .put("leftSafeInsetPercent", leftSafeInsetPercent)
        .put("crescentDirection", crescentDirection.wireName())
        .put("crescentBulgePercent", crescentBulgePercent)

    private fun LayoutMode.wireName(): String = when (this) {
        LayoutMode.Honeycomb -> MODE_HONEYCOMB
        LayoutMode.Grid -> MODE_GRID
        LayoutMode.List -> MODE_LIST
        LayoutMode.Flow -> MODE_FLOW
        LayoutMode.Ring -> MODE_RING
    }

    private fun FlowCrescentDirection.wireName(): String = when (this) {
        FlowCrescentDirection.Left -> CRESCENT_LEFT
        FlowCrescentDirection.Right -> CRESCENT_RIGHT
    }

    private fun JSONObject.requireLayoutMode(name: String): LayoutMode =
        when (requireString(name)) {
            MODE_HONEYCOMB -> LayoutMode.Honeycomb
            MODE_GRID -> LayoutMode.Grid
            MODE_LIST -> LayoutMode.List
            MODE_FLOW -> LayoutMode.Flow
            MODE_RING -> LayoutMode.Ring
            else -> throw IllegalArgumentException("Unknown layout mode")
        }

    private fun JSONObject.requireCrescentDirection(name: String): FlowCrescentDirection =
        when (requireString(name)) {
            CRESCENT_LEFT -> FlowCrescentDirection.Left
            CRESCENT_RIGHT -> FlowCrescentDirection.Right
            else -> throw IllegalArgumentException("Unknown crescent direction")
        }

    private fun JSONObject.requireString(name: String): String {
        val value = optString(name, "").trim()
        require(value.isNotEmpty()) { "$name is missing" }
        return value
    }

    private fun JSONObject.requireInt(name: String): Int {
        require(has(name) && !isNull(name)) { "$name is missing" }
        val value = get(name)
        require(value is Number) { "$name must be a number" }
        val longValue = value.toLong()
        require(longValue in Int.MIN_VALUE..Int.MAX_VALUE) { "$name is out of range" }
        require(value.toDouble() == longValue.toDouble()) { "$name must be an integer" }
        return longValue.toInt()
    }

    private fun JSONObject.requireBoolean(name: String): Boolean {
        require(has(name) && !isNull(name)) { "$name is missing" }
        val value = get(name)
        require(value is Boolean) { "$name must be a boolean" }
        return value
    }

    private fun requireDocumentSize(payload: String) {
        require(payload.isNotBlank() && payload.length <= MAX_DOCUMENT_LENGTH) {
            "Layout profile document is empty or too large"
        }
    }
}
