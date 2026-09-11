package com.flue.launcher.watchface.standard

/** Mode-specific attributes stay local to a renderer, including editor previews. */
fun watchmakerDisplayAttributes(
    attrs: Map<String, String>,
    ambientAttrs: Map<String, String> = emptyMap(),
    ambientMode: Boolean,
): Map<String, String> {
    val merged = if (ambientMode) buildMap {
        putAll(attrs)
        attrs["color_dim"]?.let { put("color", it) }
        putAll(ambientAttrs)
    } else attrs
    // Studio spells these in camel case. Canonical keys override aliases in the
    // same document, while an Ambient alias must override its Bright base value.
    val aliases = mapOf("fontSize" to "text_size", "fontColor" to "color",
        "scaleX" to "scale_x", "scaleY" to "scale_y", "skewX" to "skew_x",
        "skewY" to "skew_y", "textAlign" to "alignment")
    if (aliases.keys.none(merged::containsKey)) return merged
    return merged.toMutableMap().apply {
        aliases.forEach { (alias, canonical) ->
            if (ambientMode && alias in ambientAttrs && canonical !in ambientAttrs) {
                put(canonical, ambientAttrs.getValue(alias))
            } else if (canonical !in merged) merged[alias]?.let { put(canonical, it) }
        }
    }
}

fun watchmakerDisplayAttributes(layer: WatchmakerLayer, ambientMode: Boolean): Map<String, String> =
    watchmakerDisplayAttributes(layer.attributes,
        layer.children.firstOrNull { it.name.equals("Ambient", true) }?.attributes.orEmpty(), ambientMode)

/** Include Ambient expressions in dependency tracking and static-bake decisions. */
internal fun watchmakerLayerAttributeValues(layer: WatchmakerLayer): Collection<String> =
    layer.attributes.values + layer.children.filter { it.name.equals("Ambient", true) }.flatMap { it.attributes.values }

fun watchmakerDisplayVisible(
    display: String?,
    ambientMode: Boolean,
    evaluateExpression: (String) -> Boolean,
): Boolean = when (val value = display.orEmpty().trim().lowercase(java.util.Locale.ROOT)) {
    "", "bd", "ab", "both", "always", "y", "yes", "true", "1" -> true
    "n", "no", "false", "0", "never" -> false
    "b", "bright" -> !ambientMode
    "a", "ad", "d", "ambient", "dim" -> ambientMode
    else -> evaluateExpression(display.orEmpty().trim())
}

internal fun watchmakerTickDelay(ambientMode: Boolean, fast: Boolean): Long =
    if (ambientMode || !fast) 1_000L else 33L

/** An asynchronous capture must remain in one uninterrupted clean window. */
internal class WatchFaceCaptureEpoch {
    private var clean = false
    var epoch: Long = 0L
        private set
    fun observe(allowed: Boolean) {
        if (clean != allowed) { clean = allowed; epoch++ }
    }
    fun accepts(requestEpoch: Long): Boolean = clean && epoch == requestEpoch
}
