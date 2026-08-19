package com.flue.launcher.ui.store

import com.flue.launcher.watchface.store.WatchStoreUrlPolicy
import java.net.URI

/** Turns the store SPA's card modal into a direct, WebView-friendly detail navigation. */
internal object WatchStoreWebBridge {
    private val previewPath = Regex(
        pattern = ".*/api/watchfaces/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/preview/?$",
    )

    fun watchFacePageFromPreviewUrl(base: URI, rawPreviewUrl: String): URI? {
        val root = runCatching { WatchStoreUrlPolicy.siteRoot(base) }.getOrNull() ?: return null
        val preview = runCatching { root.resolve(URI(rawPreviewUrl.trim())) }.getOrNull() ?: return null
        if (!WatchStoreUrlPolicy.isSameWebSite(base, preview)) return null
        val id = previewPath.matchEntire(preview.rawPath.orEmpty())?.groupValues?.get(1) ?: return null
        return runCatching { WatchStoreUrlPolicy.webWatchFace(base, id) }.getOrNull()
    }

    fun installScript(base: URI): String {
        val detailPrefix = WatchStoreUrlPolicy.siteRoot(base).toASCIIString().trimEnd('/') + "/watchfaces/"
        val quotedPrefix = detailPrefix.toJavaScriptStringLiteral()
        return """
            (function() {
              if (window.__flueWatchFaceCardBridgeInstalled) return;
              window.__flueWatchFaceCardBridgeInstalled = true;
              document.addEventListener('click', function(event) {
                var target = event.target;
                var card = target && target.closest ? target.closest('.grid .card') : null;
                if (!card) return;
                var image = card.querySelector('img[src*="/api/watchfaces/"][src*="/preview"]');
                if (!image) return;
                var preview;
                try { preview = new URL(image.getAttribute('src'), location.href); } catch (_) { return; }
                if (preview.origin !== location.origin) return;
                var match = preview.pathname.match(/\/api\/watchfaces\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\/preview\/?$/i);
                if (!match) return;
                event.preventDefault();
                event.stopPropagation();
                event.stopImmediatePropagation();
                location.assign($quotedPrefix + match[1]);
              }, { capture: true });
            })();
        """.trimIndent()
    }

    private fun String.toJavaScriptStringLiteral(): String = buildString(length + 2) {
        append('"')
        for (character in this@toJavaScriptStringLiteral) {
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> append(character)
            }
        }
        append('"')
    }
}
