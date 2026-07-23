package com.flue.launcher.watchface.store

import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Pure URL construction and redirect validation, kept separate for JVM tests. */
internal object WatchStoreUrlPolicy {
    private val uuidPattern = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
    )

    fun normalizeBaseUrl(raw: String): URI {
        val parsed = parseUri(raw, "base URL")
        requireSafeHttps(parsed, "base URL")
        if (parsed.rawQuery != null) throw IllegalArgumentException("Watch store base URL cannot contain a query")
        val path = parsed.rawPath.orEmpty().trimEnd('/').ifBlank { "/" }
        return URI(
            "https",
            null,
            parsed.host.lowercase(Locale.ROOT),
            parsed.port,
            path,
            null,
            null,
        )
    }

    fun categories(base: URI): URI = endpoint(base, "categories")

    fun list(base: URI, query: WatchStoreQuery): URI {
        val parameters = ArrayList<Pair<String, String>>(6)
        parameters += "page" to query.page.toString()
        parameters += "limit" to query.limit.toString()
        parameters += "sort" to query.sort.apiValue
        query.screenType?.let { parameters += "screen_type" to it.apiValue }
        query.categorySlug?.trim()?.takeIf(String::isNotEmpty)?.let { parameters += "category" to it }
        query.search?.trim()?.takeIf(String::isNotEmpty)?.let { parameters += "search" to it }
        val encodedQuery = parameters.joinToString("&") { (name, value) ->
            "${encode(name)}=${encode(value)}"
        }
        return URI(endpoint(base, "watchfaces").toASCIIString() + "?$encodedQuery")
    }

    fun detail(base: URI, id: String): URI = endpoint(base, "watchfaces/${requireId(id)}")

    fun preview(base: URI, id: String): URI = endpoint(base, "watchfaces/${requireId(id)}/preview")

    fun download(base: URI, id: String): URI = endpoint(base, "watchfaces/${requireId(id)}/download")

    fun resolveRedirect(current: URI, location: String): URI {
        if (location.isBlank()) throw WatchStoreRedirectException("Redirect response has no Location")
        val target = try {
            current.resolve(URI(location.trim()))
        } catch (error: Exception) {
            throw WatchStoreRedirectException("Redirect Location is malformed")
        }
        try {
            requireSafeHttps(target, "redirect URL")
        } catch (error: IllegalArgumentException) {
            throw WatchStoreRedirectException(error.message ?: "Redirect URL is unsafe")
        }
        return target
    }

    fun requireId(id: String): String {
        val normalized = id.trim()
        if (!uuidPattern.matches(normalized)) {
            throw IllegalArgumentException("Watchface id must be a canonical UUID")
        }
        return normalized.lowercase(Locale.ROOT)
    }

    private fun endpoint(base: URI, relativePath: String): URI {
        val normalizedBase = normalizeBaseUrl(base.toASCIIString()).toASCIIString().trimEnd('/')
        return URI("$normalizedBase/$relativePath")
    }

    private fun parseUri(raw: String, label: String): URI = try {
        URI(raw.trim())
    } catch (error: Exception) {
        throw IllegalArgumentException("Watch store $label is malformed", error)
    }

    private fun requireSafeHttps(uri: URI, label: String) {
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            throw IllegalArgumentException("Watch store $label must use HTTPS")
        }
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawFragment != null) {
            throw IllegalArgumentException("Watch store $label has an unsafe authority")
        }
        if (uri.port == 0 || uri.port > 65_535 || uri.port < -1) {
            throw IllegalArgumentException("Watch store $label has an invalid port")
        }
        if (isLocalAddress(uri.host)) {
            throw IllegalArgumentException("Watch store $label cannot target a local address")
        }
    }

    private fun isLocalAddress(rawHost: String): Boolean {
        val host = rawHost.trim('[', ']').lowercase(Locale.ROOT)
        if (host == "localhost" || host.endsWith(".localhost")) return true
        if (':' in host) {
            // This also unwraps IPv4-mapped IPv6 literals, preventing private,
            // loopback, link-local and multicast redirects from using an
            // alternate textual spelling to bypass the SSRF boundary.
            return runCatching {
                val address = InetAddress.getByName(host)
                address.isAnyLocalAddress ||
                    address.isLoopbackAddress ||
                    address.isLinkLocalAddress ||
                    address.isSiteLocalAddress ||
                    address.isMulticastAddress
            }.getOrDefault(true)
        }
        val octets = host.split('.').map { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it == null || it !in 0..255 }) return false
        val first = octets[0]!!
        val second = octets[1]!!
        return first == 0 || first == 10 || first == 127 ||
            (first == 100 && second in 64..127) ||
            (first == 169 && second == 254) ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168) || first >= 224
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}
