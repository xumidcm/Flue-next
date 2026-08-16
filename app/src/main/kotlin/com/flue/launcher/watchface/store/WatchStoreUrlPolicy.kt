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
            parsed.scheme.lowercase(Locale.ROOT),
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

    fun authLogin(base: URI): URI = endpoint(base, "auth/login")

    fun authRegister(base: URI): URI = endpoint(base, "auth/register")

    fun myWatchFacesParse(base: URI): URI = endpoint(base, "my/watchfaces/parse")

    fun myWatchFacesList(base: URI): URI = endpoint(base, "my/watchfaces")

    /** Authenticated metadata/archive update for one of the caller's uploads. */
    fun myWatchFaceUpdate(base: URI, id: String): URI =
        endpoint(base, "my/watchfaces/${requireId(id)}")

    fun myWatchFaceDelete(base: URI, id: String): URI = endpoint(base, "my/watchfaces/${requireId(id)}")

    fun resolveRedirect(current: URI, location: String): URI {
        if (location.isBlank()) throw WatchStoreRedirectException("Redirect response has no Location")
        val target = try {
            current.resolve(URI(location.trim()))
        } catch (error: Exception) {
            throw WatchStoreRedirectException("Redirect Location is malformed")
        }
        if (current.scheme.equals("https", ignoreCase = true) &&
            target.scheme.equals("http", ignoreCase = true)
        ) {
            throw WatchStoreRedirectException("Watch store redirect cannot downgrade HTTPS to HTTP")
        }
        try {
            requirePublicHttps(target, label = "redirect URL")
        } catch (error: IllegalArgumentException) {
            throw WatchStoreRedirectException(error.message ?: "Redirect URL is unsafe")
        }
        return target
    }

    /**
     * Resolve immediately before opening the socket. Every returned address
     * must be publicly routable; a mixed public/private DNS response is
     * rejected instead of giving the connector a chance to choose the private
     * answer.
     */
    internal fun requirePublicHttps(
        uri: URI,
        label: String = "request URL",
        resolver: (String) -> Array<InetAddress> = InetAddress::getAllByName,
    ) {
        requireSafeHttps(uri, label)
        val host = uri.host ?: throw IllegalArgumentException("Watch store $label has no host")
        val addresses = try {
            resolver(host)
        } catch (error: Exception) {
            throw IllegalArgumentException("Watch store $label host cannot be resolved", error)
        }
        if (addresses.isEmpty()) {
            throw IllegalArgumentException("Watch store $label host cannot be resolved")
        }
        if (addresses.any(::isNonPublicAddress)) {
            throw IllegalArgumentException("Watch store $label cannot target a local or private address")
        }
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
        if (!uri.scheme.equals("https", ignoreCase = true) &&
            !uri.scheme.equals("http", ignoreCase = true)
        ) {
            throw IllegalArgumentException("Watch store $label must use HTTP or HTTPS")
        }
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawFragment != null) {
            throw IllegalArgumentException("Watch store $label has an unsafe authority")
        }
        if (uri.port == 0 || uri.port > 65_535 || uri.port < -1) {
            throw IllegalArgumentException("Watch store $label has an invalid port")
        }
        if (isForbiddenLiteral(uri.host)) {
            throw IllegalArgumentException("Watch store $label cannot target a local address")
        }
    }

    private fun isForbiddenLiteral(rawHost: String): Boolean {
        val host = rawHost.trim('[', ']').lowercase(Locale.ROOT)
        if (host == "localhost" || host.endsWith(".localhost")) return true
        val looksNumeric = ':' in host ||
            host.matches(Regex("^(?:0x)?[0-9a-f.]+$", RegexOption.IGNORE_CASE))
        if (!looksNumeric) return false
        return runCatching { InetAddress.getByName(host) }
            .map(::isNonPublicAddress)
            .getOrDefault(true)
    }

    private fun isNonPublicAddress(address: InetAddress): Boolean {
        if (
            address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        val bytes = address.address
        if (bytes.size == 4) {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            val third = bytes[2].toInt() and 0xff
            return first == 0 || first == 10 || first == 127 ||
                (first == 100 && second in 64..127) ||
                (first == 169 && second == 254) ||
                (first == 172 && second in 16..31) ||
                (first == 192 && second == 168) ||
                (first == 192 && second == 0 && third == 0) ||
                (first == 192 && second == 0 && third == 2) ||
                (first == 198 && second in 18..19) ||
                (first == 198 && second == 51 && third == 100) ||
                (first == 203 && second == 0 && third == 113) ||
                first >= 224
        }
        if (bytes.size == 16) {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            // fc00::/7 unique-local and 2001:db8::/32 documentation ranges.
            return first and 0xfe == 0xfc ||
                (first == 0x20 && second == 0x01 &&
                    (bytes[2].toInt() and 0xff) == 0x0d &&
                    (bytes[3].toInt() and 0xff) == 0xb8)
        }
        return true
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}
