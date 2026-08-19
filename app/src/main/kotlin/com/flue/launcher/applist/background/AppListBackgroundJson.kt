package com.flue.launcher.applist.background

import org.json.JSONObject

/**
 * 应用列表背景配置的 JSON 编解码。
 *
 * 校验风格照 [com.flue.launcher.layout.FlueLayoutProfileJson] 抄：**strict require***
 * —— 缺字段 / 类型不对 / 枚举名不认识都当场抛，而不是静默取默认值。静默取默认值的后果是
 * 老师改了设置、备份恢复后悄悄变回默认，还查不出为什么。
 *
 * 只有一份配置（不是档案库），所以没有 count 上限，只保留文档大小上限。
 */
object AppListBackgroundJson {
    private const val MAX_DOCUMENT_LENGTH = 8 * 1024

    private const val MODE_SOLID_SYSTEM = "solid_system"
    private const val MODE_SOLID_CUSTOM = "solid_custom"
    private const val MODE_WATCH_FACE = "watch_face"
    private const val MODE_CUSTOM_IMAGE = "custom_image"

    fun encode(config: AppListBackgroundConfig): String =
        config.sanitized().requireValid().toJson().toString(2)

    fun decode(payload: String): AppListBackgroundConfig {
        require(payload.isNotBlank() && payload.length <= MAX_DOCUMENT_LENGTH) {
            "App list background document is empty or too large"
        }
        val json = JSONObject(payload)
        require(json.requireInt("schemaVersion") == APP_LIST_BACKGROUND_SCHEMA_VERSION) {
            "Unsupported app list background schema"
        }
        return AppListBackgroundConfig(
            schemaVersion = APP_LIST_BACKGROUND_SCHEMA_VERSION,
            mode = json.requireMode("mode"),
            customColorArgb = json.requireInt("customColorArgb"),
            blurPercent = json.requireInt("blurPercent"),
            scrimPercent = json.requireInt("scrimPercent"),
            // 路径是可选的：没选过图时它就该缺席，而不是写一个空串占位。
            imagePath = json.optString("imagePath", "").trim().takeIf(String::isNotEmpty),
        ).sanitized().requireValid()
    }

    /** 读盘失败（旧格式 / 手改坏了 / 类型不对）时的兜底，绝不让设置页崩。 */
    fun decodeOrDefault(payload: String?): AppListBackgroundConfig {
        if (payload.isNullOrBlank()) return AppListBackgroundConfig.Default
        return runCatching { decode(payload) }.getOrDefault(AppListBackgroundConfig.Default)
    }

    private fun AppListBackgroundConfig.toJson(): JSONObject = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("mode", mode.wireName())
        .put("customColorArgb", customColorArgb)
        .put("blurPercent", blurPercent)
        .put("scrimPercent", scrimPercent)
        .apply { imagePath?.let { put("imagePath", it) } }

    private fun AppListBackgroundMode.wireName(): String = when (this) {
        AppListBackgroundMode.SOLID_SYSTEM -> MODE_SOLID_SYSTEM
        AppListBackgroundMode.SOLID_CUSTOM -> MODE_SOLID_CUSTOM
        AppListBackgroundMode.WATCH_FACE -> MODE_WATCH_FACE
        AppListBackgroundMode.CUSTOM_IMAGE -> MODE_CUSTOM_IMAGE
    }

    private fun JSONObject.requireMode(name: String): AppListBackgroundMode =
        when (requireString(name)) {
            MODE_SOLID_SYSTEM -> AppListBackgroundMode.SOLID_SYSTEM
            MODE_SOLID_CUSTOM -> AppListBackgroundMode.SOLID_CUSTOM
            MODE_WATCH_FACE -> AppListBackgroundMode.WATCH_FACE
            MODE_CUSTOM_IMAGE -> AppListBackgroundMode.CUSTOM_IMAGE
            else -> throw IllegalArgumentException("Unknown app list background mode")
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
}
