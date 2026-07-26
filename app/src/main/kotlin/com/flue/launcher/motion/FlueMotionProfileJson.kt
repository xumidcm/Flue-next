package com.flue.launcher.motion

import org.json.JSONArray
import org.json.JSONObject

object FlueMotionProfileJson {
    private const val TYPE_CUBIC = "cubicBezier"
    private const val TYPE_SPRING = "spring"
    private const val MAX_PROFILE_COUNT = 64
    private const val MAX_DOCUMENT_LENGTH = 256 * 1024

    fun encodeProfile(profile: FlueMotionProfile): String =
        profile.requireValid().toJson().toString(2)

    fun decodeProfile(payload: String): FlueMotionProfile {
        requireDocumentSize(payload)
        return parseProfile(JSONObject(payload)).requireValid()
    }

    fun encodeLibrary(profiles: List<FlueMotionProfile>): String {
        require(profiles.size <= MAX_PROFILE_COUNT) { "Too many motion profiles" }
        require(profiles.map { it.id }.toSet().size == profiles.size) {
            "Duplicate motion profile ids"
        }
        val root = JSONObject()
            .put("schemaVersion", FLUE_MOTION_SCHEMA_VERSION)
            .put(
                "profiles",
                JSONArray().apply {
                    profiles.forEach { put(it.requireValid().toJson()) }
                },
            )
        return root.toString(2)
    }

    fun decodeLibrary(payload: String): List<FlueMotionProfile> {
        requireDocumentSize(payload)
        val root = JSONObject(payload)
        require(root.requireInt("schemaVersion") == FLUE_MOTION_SCHEMA_VERSION) {
            "Unsupported motion library schema"
        }
        val profilesJson = root.optJSONArray("profiles")
            ?: throw IllegalArgumentException("Motion library profiles are missing")
        require(profilesJson.length() <= MAX_PROFILE_COUNT) { "Too many motion profiles" }
        val profiles = buildList {
            repeat(profilesJson.length()) { index ->
                val value = profilesJson.optJSONObject(index)
                    ?: throw IllegalArgumentException("profiles[$index] must be an object")
                add(parseProfile(value).requireValid())
            }
        }
        require(profiles.map { it.id }.toSet().size == profiles.size) {
            "Duplicate motion profile ids"
        }
        return profiles
    }

    private fun parseProfile(json: JSONObject): FlueMotionProfile = FlueMotionProfile(
        schemaVersion = json.requireInt("schemaVersion"),
        id = json.requireString("id"),
        name = json.requireString("name"),
        bounceEnabled = json.requireBoolean("bounceEnabled"),
        edgeStrength = json.requireInt("edgeStrength"),
        appListEnter = json.requirePhase("appListEnter"),
        appLaunch = json.requirePhase("appLaunch"),
        appReturn = json.requirePhase("appReturn"),
        pullToFace = json.requirePhase("pullToFace"),
        edgeBounce = json.requirePhase("edgeBounce"),
    )

    private fun FlueMotionProfile.toJson(): JSONObject = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("id", id)
        .put("name", name)
        .put("bounceEnabled", bounceEnabled)
        .put("edgeStrength", edgeStrength)
        .put("appListEnter", appListEnter.toJson())
        .put("appLaunch", appLaunch.toJson())
        .put("appReturn", appReturn.toJson())
        .put("pullToFace", pullToFace.toJson())
        .put("edgeBounce", edgeBounce.toJson())

    private fun MotionPhase.toJson(): JSONObject = JSONObject()
        .put("durationMillis", durationMillis)
        .put("handoffMillis", handoffMillis)
        .put("easing", easing.toJson())

    private fun MotionEasing.toJson(): JSONObject = when (this) {
        is MotionEasing.CubicBezier -> JSONObject()
            .put("type", TYPE_CUBIC)
            .put("x1", x1.toDouble())
            .put("y1", y1.toDouble())
            .put("x2", x2.toDouble())
            .put("y2", y2.toDouble())

        is MotionEasing.Spring -> JSONObject()
            .put("type", TYPE_SPRING)
            .put("dampingRatio", dampingRatio.toDouble())
            .put("stiffness", stiffness.toDouble())
    }

    private fun JSONObject.requirePhase(name: String): MotionPhase {
        val value = optJSONObject(name)
            ?: throw IllegalArgumentException("$name must be an object")
        return MotionPhase(
            durationMillis = value.requireInt("durationMillis"),
            handoffMillis = value.requireInt("handoffMillis"),
            easing = value.requireEasing("easing"),
        )
    }

    private fun JSONObject.requireEasing(name: String): MotionEasing {
        val value = optJSONObject(name)
            ?: throw IllegalArgumentException("$name must be an object")
        return when (value.requireString("type")) {
            TYPE_CUBIC -> MotionEasing.CubicBezier(
                x1 = value.requireFloat("x1"),
                y1 = value.requireFloat("y1"),
                x2 = value.requireFloat("x2"),
                y2 = value.requireFloat("y2"),
            )

            TYPE_SPRING -> MotionEasing.Spring(
                dampingRatio = value.requireFloat("dampingRatio"),
                stiffness = value.requireFloat("stiffness"),
            )

            else -> throw IllegalArgumentException("Unknown motion easing type")
        }
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

    private fun JSONObject.requireFloat(name: String): Float {
        require(has(name) && !isNull(name)) { "$name is missing" }
        val value = get(name)
        require(value is Number) { "$name must be a number" }
        return value.toFloat()
    }

    private fun JSONObject.requireBoolean(name: String): Boolean {
        require(has(name) && !isNull(name)) { "$name is missing" }
        val value = get(name)
        require(value is Boolean) { "$name must be a boolean" }
        return value
    }

    private fun requireDocumentSize(payload: String) {
        require(payload.isNotBlank() && payload.length <= MAX_DOCUMENT_LENGTH) {
            "Motion profile document is empty or too large"
        }
    }
}
