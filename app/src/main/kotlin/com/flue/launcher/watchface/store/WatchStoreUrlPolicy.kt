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

    /** 与上游注册校验同规则：字母、数字、下划线，3–50 字符。 */
    private val usernamePattern = Regex("^[a-zA-Z0-9_]{3,50}$")

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
        val parameters = ArrayList<Pair<String, String>>(8)
        parameters += "page" to query.page.toString()
        parameters += "limit" to query.limit.toString()
        parameters += "sort" to query.sort.apiValue
        query.screenType?.let { parameters += "screen_type" to it.apiValue }
        query.categorySlug?.trim()?.takeIf(String::isNotEmpty)?.let { parameters += "category" to it }
        query.search?.trim()?.takeIf(String::isNotEmpty)?.let { parameters += "search" to it }
        query.tag?.trim()?.takeIf(String::isNotEmpty)?.let { parameters += "tag" to it }
        query.wmVersion?.trim()?.takeIf(String::isNotEmpty)?.let { parameters += "wm_version" to it }
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

    // ── 个人中心（B1）────────────────────────────────────────────────────────

    fun myAccount(base: URI): URI = endpoint(base, "my/me")

    /** `PATCH`：description / timezone / language 的任意子集。 */
    fun myProfile(base: URI): URI = endpoint(base, "my/profile")

    /** `POST` multipart：PNG/JPEG/WebP ≤ 2MB，上游按 magic bytes 校验而不是扩展名。 */
    fun myProfileAvatar(base: URI): URI = endpoint(base, "my/profile/avatar")

    fun myPassword(base: URI): URI = endpoint(base, "my/password")

    // ── 上架状态（B1）────────────────────────────────────────────────────────

    fun myWatchFaceUnlist(base: URI, id: String): URI =
        endpoint(base, "my/watchfaces/${requireId(id)}/unlist")

    fun myWatchFaceRepublish(base: URI, id: String): URI =
        endpoint(base, "my/watchfaces/${requireId(id)}/republish")

    /** 上传者统计。`days` 上游限 1..365。 */
    fun myStats(base: URI, days: Int): URI =
        URI(endpoint(base, "my/stats").toASCIIString() + "?days=${requireDays(days)}")

    // ── 评论（B1）────────────────────────────────────────────────────────────

    fun comments(base: URI, watchFaceId: String): URI =
        endpoint(base, "watchfaces/${requireId(watchFaceId)}/comments")

    /** 编辑 / 删除自己的评论。 */
    fun myComment(base: URI, commentId: String): URI =
        endpoint(base, "my/comments/${requireId(commentId)}")

    // ── 喜欢（B1）────────────────────────────────────────────────────────────

    fun favorite(base: URI, watchFaceId: String): URI =
        endpoint(base, "watchfaces/${requireId(watchFaceId)}/favorite")

    fun myFavorites(base: URI): URI = endpoint(base, "my/favorites")

    fun myFavoriteState(base: URI, watchFaceId: String): URI =
        endpoint(base, "my/watchfaces/${requireId(watchFaceId)}/favorite")

    // ── 评分（B1）────────────────────────────────────────────────────────────

    fun rating(base: URI, watchFaceId: String): URI =
        endpoint(base, "watchfaces/${requireId(watchFaceId)}/rating")

    fun myRating(base: URI, watchFaceId: String): URI =
        endpoint(base, "my/watchfaces/${requireId(watchFaceId)}/rating")

    // ── 通知（B1）────────────────────────────────────────────────────────────

    /** `limit` 上游限 1..100，默认 20。 */
    fun myNotifications(base: URI, limit: Int): URI =
        URI(endpoint(base, "my/notifications").toASCIIString() + "?limit=${requireLimit(limit)}")

    fun myNotificationsUnreadCount(base: URI): URI = endpoint(base, "my/notifications/unread-count")

    fun myNotificationRead(base: URI, notificationId: String): URI =
        endpoint(base, "my/notifications/${requireId(notificationId)}/read")

    fun myNotificationsReadAll(base: URI): URI = endpoint(base, "my/notifications/read-all")

    // ── 创作者（B1）──────────────────────────────────────────────────────────

    fun topCreators(base: URI, limit: Int): URI =
        URI(endpoint(base, "creators/top").toASCIIString() + "?limit=${requireCreatorLimit(limit)}")

    fun creator(base: URI, username: String): URI =
        endpoint(base, "creators/${requireUsername(username)}")

    fun creatorWatchFaces(base: URI, username: String, page: Int, limit: Int): URI {
        val path = endpoint(base, "creators/${requireUsername(username)}/watchfaces").toASCIIString()
        return URI("$path?page=${page.coerceAtLeast(1)}&limit=${requireLimit(limit)}")
    }

    fun creatorAvatar(base: URI, username: String): URI =
        endpoint(base, "creators/${requireUsername(username)}/avatar")

    // ── 合集（B1）────────────────────────────────────────────────────────────

    fun myCollections(base: URI): URI = endpoint(base, "my/collections")

    fun myCollection(base: URI, collectionId: String): URI =
        endpoint(base, "my/collections/${requireId(collectionId)}")

    fun myCollectionItems(base: URI, collectionId: String): URI =
        endpoint(base, "my/collections/${requireId(collectionId)}/items")

    fun myCollectionItem(base: URI, collectionId: String, watchFaceId: String): URI =
        endpoint(
            base,
            "my/collections/${requireId(collectionId)}/items/${requireId(watchFaceId)}",
        )

    // ── 举报与功能开关（B1）──────────────────────────────────────────────────

    fun report(base: URI, watchFaceId: String): URI =
        endpoint(base, "watchfaces/${requireId(watchFaceId)}/report")

    /** 公开的功能开关。UI 必须尊重它，禁用的功能不要让用户点了才吃 403。 */
    fun config(base: URI): URI = endpoint(base, "config")

    // ── 网页版地址（G5 / H）──────────────────────────────────────────────────

    /**
     * 站点根。API base 一般是 `https://host/api`，网页在站点根上，所以**剥掉尾部的 `/api`**。
     *
     * 不写死域名：老师可以在设置里换自定义市场地址，网页版入口要跟着换。
     */
    fun siteRoot(base: URI): URI {
        val normalized = normalizeBaseUrl(base.toASCIIString())
        val path = normalized.rawPath.orEmpty().trimEnd('/')
        val trimmed = if (path.endsWith("/api", ignoreCase = true)) {
            path.dropLast(4)
        } else {
            path
        }
        return URI(
            normalized.scheme,
            null,
            normalized.host,
            normalized.port,
            trimmed.ifBlank { "/" },
            null,
            null,
        )
    }

    /** 决策 9 的六个入口。全部走 C1 的内置 WebView，不外跳浏览器。 */
    fun webPage(base: URI, page: WatchStoreWebPage): URI {
        val root = siteRoot(base).toASCIIString().trimEnd('/')
        return URI(root + page.path)
    }

    fun webWatchFace(base: URI, id: String): URI =
        URI(siteRoot(base).toASCIIString().trimEnd('/') + "/watchfaces/${requireId(id)}")

    /** 注意是单数 `creator`，与列表页的 `/creators` 不同（老师给的实例：`/creator/ailife`）。 */
    fun webCreator(base: URI, username: String): URI =
        URI(siteRoot(base).toASCIIString().trimEnd('/') + "/creator/${requireUsername(username)}")

    // ── 内置 WebView 的导航白名单（C1）────────────────────────────────────────

    /**
     * 这个地址是不是**站内**。
     *
     * 白名单不写死 `jbwatchface.ai-life.xyz`，而是取「当前配置的市场地址所在站点」：
     * scheme + host + port 三者都要与 [siteRoot] 相同。理由是 G3 允许老师换自定义市场
     * 地址 —— 写死域名的话换了地址网页版入口就全废，而**信任边界本来就等于 API 的信任
     * 边界**（同一个站点既提供 API 又提供网页）。
     *
     * host 比较大小写不敏感、按 ASCII 归一（IDN 域名 [URI] 已经 punycode 化过了）。
     */
    fun isSameWebSite(base: URI, candidate: URI): Boolean {
        val root = runCatching { siteRoot(base) }.getOrNull() ?: return false
        val candidateHost = candidate.host?.lowercase(Locale.ROOT) ?: return false
        val rootHost = root.host?.lowercase(Locale.ROOT) ?: return false
        if (candidateHost != rootHost) return false
        if (!candidate.scheme.equals(root.scheme, ignoreCase = true)) return false
        return effectivePort(candidate) == effectivePort(root)
    }

    /**
     * 把外部传进来的 `EXTRA_URL` 收敛成一个**可以加载**的站内地址。
     *
     * 六个网页入口打的是六个不同地址，所以宿主收 URL 参数而不是写死首页。但参数是从
     * Intent 来的（别的 App 也能发），所以：解析失败、不是 http(s)、不在站内 —— 一律
     * **退回站点根**，绝不加载传进来的任意地址。
     */
    fun webUrlOrSiteRoot(base: URI, raw: String?): URI {
        val fallback = siteRoot(base)
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return fallback
        val candidate = runCatching { URI(trimmed) }.getOrNull() ?: return fallback
        if (
            !candidate.scheme.equals("https", ignoreCase = true) &&
            !candidate.scheme.equals("http", ignoreCase = true)
        ) {
            return fallback
        }
        if (candidate.rawUserInfo != null) return fallback
        return if (isSameWebSite(base, candidate)) candidate else fallback
    }

    /**
     * 服务端给的图片地址（头像）解析成可以下载的绝对地址，认不出来返回 null。
     *
     * 上游可能回相对路径（`/uploads/avatars/x.webp`）也可能回绝对地址，所以先按
     * [siteRoot] 解析。**结果必须仍在站内**：头像图是远端数据，跟着它跳到任意主机等于
     * 让服务端指挥客户端发请求。真放到 CDN 上的话这里会返回 null，界面退回首字母头像 ——
     * 少一张图，好过多一条任意出站请求。
     */
    fun siteImageUrlOrNull(base: URI, raw: String?): URI? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val root = runCatching { siteRoot(base) }.getOrNull() ?: return null
        val resolved = runCatching { root.resolve(URI(trimmed)) }.getOrNull() ?: return null
        if (resolved.rawUserInfo != null || resolved.rawFragment != null) return null
        return resolved.takeIf { isSameWebSite(base, it) }
    }

    /** 缺省端口按 scheme 补齐，`https://h` 与 `https://h:443` 才算同一个站点。 */
    private fun effectivePort(uri: URI): Int {
        if (uri.port != -1) return uri.port
        return if (uri.scheme.equals("http", ignoreCase = true)) 80 else 443
    }

    /**
     * 网页里点「下载」时那条链接背后的表盘 id（C3），认不出来返回 null。
     *
     * 站点的下载链接就是 API 的下载端点，形如 `…/watchfaces/{uuid}/download`（`/api` 前缀
     * 可有可无）。宿主抓到它之后**不自己装**：把 id 交回表盘市场，走那边已经跑通的
     * 「下载 → 校验 → 安装事务 → 目录对账」整条路，绝不在 WebView 这边另写一套安装逻辑。
     *
     * 必须先过 [isSameWebSite]：站外的同形链接不能当成自家下载。
     */
    fun downloadWatchFaceIdOrNull(base: URI, rawUrl: String?): String? {
        val trimmed = rawUrl?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val candidate = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (!isSameWebSite(base, candidate)) return null
        val segments = candidate.rawPath.orEmpty().split('/').filter(String::isNotBlank)
        if (segments.size < 3) return null
        if (!segments.last().equals("download", ignoreCase = true)) return null
        if (!segments[segments.size - 3].equals("watchfaces", ignoreCase = true)) return null
        return runCatching { requireId(segments[segments.size - 2]) }.getOrNull()
    }

    fun resolveRedirect(current: URI, location: String): URI {        if (location.isBlank()) throw WatchStoreRedirectException("Redirect response has no Location")
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

    /**
     * 用户名进 URL 之前先过字符白名单。
     *
     * 与上游注册时的校验同一条规则（`^[a-zA-Z0-9_]+$`、3–50 字符）。这不是抄一遍参数校验，
     * 而是**路径注入的防线**：用户名来自服务端响应（创作者列表），拼进路径前必须确认它不含
     * `/`、`..`、`?`、`%` 之类能改变目标地址的字符。
     */
    fun requireUsername(username: String): String {
        val normalized = username.trim()
        if (!usernamePattern.matches(normalized)) {
            throw IllegalArgumentException("Watch store username must be 3-50 letters, digits or underscores")
        }
        return normalized
    }

    /** 列表类接口的 `limit`，上游限 1..100。 */
    private fun requireLimit(limit: Int): Int = limit.coerceIn(1, 100)

    /** `creators/top` 的 `limit`，上游限 1..50。 */
    private fun requireCreatorLimit(limit: Int): Int = limit.coerceIn(1, 50)

    /** `my/stats` 的 `days`，上游限 1..365。 */
    private fun requireDays(days: Int): Int = days.coerceIn(1, 365)

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
