package com.flue.launcher.watchface.store

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.InetAddress
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE)
class WatchStoreParsingTest {
    @Test
    fun `page summaries retain uploader username as author`() {
        val page = WatchStoreJsonParser.page(
            """
            {
              "data": [{
                "id": "face-1",
                "name": "Test face",
                "screen_type": "round",
                "category": {"id": "cat-1", "name": "Test", "slug": "test"},
                "preview_url": "/previews/face-1.png",
                "download_count": 12,
                "created_at": "2026-09-05T00:00:00Z",
                "uploader": {"id": "user-1", "username": "Alice"}
              }],
              "pagination": {"page": 1, "limit": 10, "total": 1, "total_pages": 1}
            }
            """.trimIndent(),
        )

        assertEquals("Alice", page.data.single().author)
    }

    @Test
    fun `upload limit matches current community store contract`() {
        assertEquals(20L * 1024L * 1024L, HttpWatchStoreApi.MAX_UPLOAD_BYTES)
    }

    @Test
    fun `avatar transport limit matches the pure avatar rules`() {
        assertEquals(WatchStoreAvatarRules.MAX_BYTES, HttpWatchStoreApi.MAX_AVATAR_BYTES)
    }
    @Test
    fun `ui preferences persist custom http market and recover default`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("flue_watch_store_ui", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        val preferences = WatchStoreUiPreferences(context)
        assertEquals(DEFAULT_WATCH_STORE_BASE_URL, preferences.readBaseUrl())
        assertEquals("http://watch.example/api", preferences.writeBaseUrl("http://watch.example/api/"))
        assertEquals("http://watch.example/api", preferences.readBaseUrl())
        assertThrows(IllegalArgumentException::class.java) {
            preferences.writeBaseUrl("ftp://watch.example/api")
        }
        preferences.resetBaseUrl()
        assertEquals(DEFAULT_WATCH_STORE_BASE_URL, preferences.readBaseUrl())
    }
    @Test
    fun `url policy builds encoded paginated endpoint`() {
        val base = WatchStoreUrlPolicy.normalizeBaseUrl(DEFAULT_WATCH_STORE_BASE_URL)
        val url = WatchStoreUrlPolicy.list(
            base,
            WatchStoreQuery(
                page = 3,
                limit = 50,
                sort = WatchStoreSort.POPULAR,
                screenType = WatchStoreScreenType.ROUND,
                categorySlug = "sport",
                search = "猫 & clock",
            ),
        )

        assertEquals("https", url.scheme)
        assertEquals("jbwatchface.ai-life.xyz", url.host)
        assertTrue(url.rawQuery.orEmpty().contains("page=3"))
        assertTrue(url.rawQuery.orEmpty().contains("limit=50"))
        assertTrue(url.rawQuery.orEmpty().contains("screen_type=round"))
        assertTrue(url.rawQuery.orEmpty().contains("search=%E7%8C%AB%20%26%20clock"))
    }

    @Test
    fun `url policy builds authenticated upload update endpoint`() {
        val base = WatchStoreUrlPolicy.normalizeBaseUrl(DEFAULT_WATCH_STORE_BASE_URL)
        val id = "b749ed8e-c9f1-4f82-bed6-9ea14f5252b9"

        assertEquals(
            "https://jbwatchface.ai-life.xyz/api/my/watchfaces/$id",
            WatchStoreUrlPolicy.myWatchFaceUpdate(base, id).toASCIIString(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            WatchStoreUrlPolicy.myWatchFaceUpdate(base, "../watchfaces/other")
        }
    }

    @Test
    fun `url policy rejects insecure bases and redirects`() {
        assertEquals(
            "http://example.com/api",
            WatchStoreUrlPolicy.normalizeBaseUrl("http://example.com/api/").toASCIIString(),
        )
        assertThrows(WatchStoreRedirectException::class.java) {
            WatchStoreUrlPolicy.resolveRedirect(
                URI("https://example.com/api/watchfaces/1"),
                "http://example.com/file.watch",
            )
        }
        assertThrows(WatchStoreRedirectException::class.java) {
            WatchStoreUrlPolicy.resolveRedirect(
                URI("https://example.com/api/watchfaces/1"),
                "https://localhost/file.watch",
            )
        }
        assertThrows(WatchStoreRedirectException::class.java) {
            WatchStoreUrlPolicy.resolveRedirect(
                URI("https://example.com/api/watchfaces/1"),
                "https://[fe80::1]/file.watch",
            )
        }
        assertThrows(WatchStoreRedirectException::class.java) {
            WatchStoreUrlPolicy.resolveRedirect(
                URI("https://example.com/api/watchfaces/1"),
                "https://[::ffff:127.0.0.1]/file.watch",
            )
        }
    }

    @Test
    fun `url policy preserves http for explicit custom market`() {
        val base = WatchStoreUrlPolicy.normalizeBaseUrl("http://watch.example:8080/api/")
        assertEquals("http", base.scheme)
        assertEquals("http://watch.example:8080/api/categories", WatchStoreUrlPolicy.categories(base).toASCIIString())
        assertEquals(
            "http://8.8.8.8/file.watch",
            WatchStoreUrlPolicy.resolveRedirect(
                URI("http://8.8.8.8/api/watchfaces/1"),
                "/file.watch",
            ).toASCIIString(),
        )
    }

    @Test
    fun `private market base requires explicit opt in while redirects remain strict`() {
        assertThrows(IllegalArgumentException::class.java) {
            WatchStoreUrlPolicy.normalizeBaseUrl("http://192.168.1.10:3000/api")
        }
        assertEquals(
            "http://192.168.1.10:3000/api",
            WatchStoreUrlPolicy.normalizeBaseUrl(
                "http://192.168.1.10:3000/api",
                allowPrivateNetworkBase = true,
            ).toASCIIString(),
        )
        assertThrows(WatchStoreRedirectException::class.java) {
            WatchStoreUrlPolicy.resolveRedirect(
                URI("http://192.168.1.10:3000/api/watchfaces/1"),
                "http://127.0.0.1/private",
            )
        }
    }

    @Test
    fun `url policy rejects hostnames resolving to private addresses or no addresses`() {
        val request = URI("https://watch.example/api")
        assertThrows(IllegalArgumentException::class.java) {
            WatchStoreUrlPolicy.requirePublicHttps(request) {
                arrayOf(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 7)))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            WatchStoreUrlPolicy.requirePublicHttps(request) { emptyArray() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            WatchStoreUrlPolicy.requirePublicHttps(request) {
                throw java.net.UnknownHostException("unresolved")
            }
        }
    }

    @Test
    fun `parser reads categories page and detail contract`() {
        val categories = WatchStoreJsonParser.categories(
            """{"data":[{"id":"cat-1","name":"Sport","slug":"sport"}]}""",
        )
        assertEquals(1, categories.size)
        assertEquals("sport", categories.single().slug)

        val page = WatchStoreJsonParser.page(
            """
            {
              "data":[{
                "id":"wf-1","name":"Runner","screen_type":"round",
                "category":{"id":"cat-1","name":"Sport","slug":"sport"},
                "preview_url":"/api/watchfaces/wf-1/preview",
                "download_count":12,"created_at":"2026-07-21T00:00:00.000Z"
              }],
              "pagination":{"page":1,"limit":20,"total":1,"total_pages":1}
            }
            """.trimIndent(),
        )
        assertEquals("Runner", page.data.single().name)
        assertTrue(!page.pagination.hasNextPage)

        val detail = WatchStoreJsonParser.detail(
            """
            {
              "id":"wf-1","name":"Runner","description":"A face",
              "screen_type":"square",
              "category":{"id":"cat-1","name":"Sport","slug":"sport"},
              "preview_url":"/api/watchfaces/wf-1/preview",
              "download_url":"/api/watchfaces/wf-1/download",
              "download_count":12,"created_at":"2026-07-21T00:00:00.000Z",
              "uploader":{"id":"user-1","username":"alice"}
            }
            """.trimIndent(),
        )
        assertEquals("alice", detail.uploader.username)
        assertEquals(WatchStoreScreenType.SQUARE, detail.screenType)
    }

    @Test
    fun `parser reads upload metadata returned by parse endpoint`() {
        val result = WatchStoreJsonParser.uploadParse(
            """
            {
              "parsed": {
                "title": "Morning Run",
                "description": "A clean sports face",
                "tags": ["sport", "outdoor"],
                "author": "alice",
                "shape": "R",
                "screen_type": "round",
                "width": 512,
                "height": 512,
                "wm_version": "2.0",
                "wm_protected": false,
                "layer_count": 12
              },
              "scan": {"success": true, "verdict": "ok"}
            }
            """.trimIndent(),
        )

        assertEquals("Morning Run", result.title)
        assertEquals("A clean sports face", result.description)
        assertEquals(listOf("sport", "outdoor"), result.tags)
        assertEquals("alice", result.author)
        assertEquals(WatchStoreScreenType.ROUND, result.screenType)
        assertEquals("2.0", result.wmVersion)
        assertEquals(12, result.layerCount)
        assertTrue(result.scanSuccess)
    }

    @Test
    fun `parser rejects malformed payload and transport enforces byte limit`() {
        assertThrows(WatchStoreProtocolException::class.java) {
            WatchStoreJsonParser.page("{\"data\":[]}")
        }
        assertThrows(WatchStorePayloadTooLargeException::class.java) {
            HttpWatchStoreApi.readLimited(
                "123456789".byteInputStream(StandardCharsets.UTF_8),
                8,
            )
        }
    }

    @Test
    fun `transport preserves coroutine cancellation`() {
        val api = HttpWatchStoreApi(
            baseUrl = "https://8.8.8.8/api",
            connectionFactory = { throw CancellationException("cancelled") },
        )

        assertThrows(CancellationException::class.java) {
            api.getCategories()
        }
    }

    @Test
    fun `uploaded watch face edit uses authenticated put and keeps archive optional`() {
        lateinit var connection: RecordingHttpURLConnection
        val api = HttpWatchStoreApi(
            baseUrl = "https://8.8.8.8/api",
            connectionFactory = { url ->
                RecordingHttpURLConnection(url).also { connection = it }
            },
        )

        api.updateMyWatchFace(
            token = "test-token",
            id = "b749ed8e-c9f1-4f82-bed6-9ea14f5252b9",
            name = "新名称",
            description = "新的描述",
            screenType = WatchStoreScreenType.ROUND,
            categoryId = "675f3e3c-a644-4954-a3d8-8d300c5c36a4",
        )

        val body = connection.writtenBody.toString(StandardCharsets.UTF_8.name())
        assertEquals("PUT", connection.requestMethod)
        assertEquals("Bearer test-token", connection.getRequestProperty("Authorization"))
        assertTrue(body.contains("name=\"name\""))
        assertTrue(body.contains("\r\n新名称\r\n"))
        assertTrue(body.contains("name=\"screen_type\""))
        assertTrue(body.contains("\r\nround\r\n"))
        assertTrue(!body.contains("name=\"file\""))
    }

    @Test
    fun `upload follows parse then create endpoints with bearer on both requests`() {
        val archive = java.nio.file.Files.createTempFile("flue-upload", ".watch").toFile()
        archive.writeBytes("watch-data".toByteArray())
        val connections = mutableListOf<RecordingHttpURLConnection>()
        val api = HttpWatchStoreApi(
            baseUrl = "https://8.8.8.8/api",
            connectionFactory = { url ->
                RecordingHttpURLConnection(url).also { connections += it }
            },
        )
        try {
            api.uploadWatchFace(
                token = "test-token",
                file = archive,
                name = "Gravity",
                description = "Gravity simulation",
                screenType = WatchStoreScreenType.ROUND,
                categoryId = "675f3e3c-a644-4954-a3d8-8d300c5c36a4",
            )
            assertEquals(2, connections.size)
            assertTrue(connections[0].targetUrl.path.endsWith("/api/my/watchfaces/parse"))
            assertTrue(connections[1].targetUrl.path.endsWith("/api/my/watchfaces"))
            connections.forEach { assertEquals("Bearer test-token", it.getRequestProperty("Authorization")) }
            assertTrue(!connections[0].writtenBody.toString(StandardCharsets.UTF_8.name()).contains("name=\"name\""))
            val finalBody = connections[1].writtenBody.toString(StandardCharsets.UTF_8.name())
            assertTrue(finalBody.contains("name=\"name\""))
            assertTrue(finalBody.contains("\r\nGravity\r\n"))
        } finally {
            archive.delete()
        }
    }

    private class RecordingHttpURLConnection(url: URL) : HttpURLConnection(url) {
        val targetUrl: URL = url
        val writtenBody = ByteArrayOutputStream()

        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        override fun getOutputStream(): OutputStream = writtenBody

        override fun getInputStream(): InputStream = ByteArrayInputStream("{}".toByteArray())

        override fun getResponseCode(): Int = HTTP_OK
    }

    // ── B1：补齐上游协议 ─────────────────────────────────────────────────────

    @Test
    fun `list query carries the tag and format filters`() {
        val base = WatchStoreUrlPolicy.normalizeBaseUrl(DEFAULT_WATCH_STORE_BASE_URL)
        val url = WatchStoreUrlPolicy.list(
            base,
            WatchStoreQuery(sort = WatchStoreSort.TRENDING, tag = "运动 & 户外", wmVersion = "2.0"),
        )
        assertTrue(url.rawQuery.orEmpty().contains("sort=trending"))
        assertTrue(url.rawQuery.orEmpty().contains("tag=%E8%BF%90%E5%8A%A8%20%26%20%E6%88%B7%E5%A4%96"))
        assertTrue(url.rawQuery.orEmpty().contains("wm_version=2.0"))
        // 上游把 wm_version 限死两个值，别的在构造时就该被拦住而不是等 400。
        assertThrows(IllegalArgumentException::class.java) { WatchStoreQuery(wmVersion = "3.0") }
    }

    @Test
    fun `every new endpoint lands on the documented path`() {
        val base = WatchStoreUrlPolicy.normalizeBaseUrl(DEFAULT_WATCH_STORE_BASE_URL)
        val id = "b749ed8e-c9f1-4f82-bed6-9ea14f5252b9"
        val root = "https://jbwatchface.ai-life.xyz/api"

        assertEquals("$root/my/me", WatchStoreUrlPolicy.myAccount(base).toASCIIString())
        assertEquals("$root/my/profile", WatchStoreUrlPolicy.myProfile(base).toASCIIString())
        assertEquals("$root/my/profile/avatar", WatchStoreUrlPolicy.myProfileAvatar(base).toASCIIString())
        assertEquals("$root/my/password", WatchStoreUrlPolicy.myPassword(base).toASCIIString())
        assertEquals("$root/my/watchfaces/$id/unlist", WatchStoreUrlPolicy.myWatchFaceUnlist(base, id).toASCIIString())
        assertEquals("$root/my/watchfaces/$id/republish", WatchStoreUrlPolicy.myWatchFaceRepublish(base, id).toASCIIString())
        assertEquals("$root/watchfaces/$id/comments", WatchStoreUrlPolicy.comments(base, id).toASCIIString())
        assertEquals("$root/my/comments/$id", WatchStoreUrlPolicy.myComment(base, id).toASCIIString())
        assertEquals("$root/watchfaces/$id/favorite", WatchStoreUrlPolicy.favorite(base, id).toASCIIString())
        assertEquals("$root/my/favorites", WatchStoreUrlPolicy.myFavorites(base).toASCIIString())
        assertEquals("$root/my/watchfaces/$id/favorite", WatchStoreUrlPolicy.myFavoriteState(base, id).toASCIIString())
        assertEquals("$root/watchfaces/$id/rating", WatchStoreUrlPolicy.rating(base, id).toASCIIString())
        assertEquals("$root/my/watchfaces/$id/rating", WatchStoreUrlPolicy.myRating(base, id).toASCIIString())
        assertEquals("$root/my/notifications/unread-count", WatchStoreUrlPolicy.myNotificationsUnreadCount(base).toASCIIString())
        assertEquals("$root/my/notifications/$id/read", WatchStoreUrlPolicy.myNotificationRead(base, id).toASCIIString())
        assertEquals("$root/my/notifications/read-all", WatchStoreUrlPolicy.myNotificationsReadAll(base).toASCIIString())
        assertEquals("$root/creators/ailife", WatchStoreUrlPolicy.creator(base, "ailife").toASCIIString())
        assertEquals("$root/creators/ailife/avatar", WatchStoreUrlPolicy.creatorAvatar(base, "ailife").toASCIIString())
        assertEquals("$root/my/collections", WatchStoreUrlPolicy.myCollections(base).toASCIIString())
        assertEquals("$root/my/collections/$id/items", WatchStoreUrlPolicy.myCollectionItems(base, id).toASCIIString())
        assertEquals("$root/my/collections/$id/items/$id", WatchStoreUrlPolicy.myCollectionItem(base, id, id).toASCIIString())
        assertEquals("$root/watchfaces/$id/report", WatchStoreUrlPolicy.report(base, id).toASCIIString())
        assertEquals("$root/config", WatchStoreUrlPolicy.config(base).toASCIIString())
    }

    @Test
    fun `paged endpoints clamp their limits to what upstream accepts`() {
        val base = WatchStoreUrlPolicy.normalizeBaseUrl(DEFAULT_WATCH_STORE_BASE_URL)
        // 通知 1..100、创作者 1..50、统计 1..365。夹取而不是抛错：这些值来自 UI 的
        // 分页状态，越界应当收敛成合法请求，而不是让一次刷新失败。
        assertTrue(WatchStoreUrlPolicy.myNotifications(base, 5_000).rawQuery!!.contains("limit=100"))
        assertTrue(WatchStoreUrlPolicy.myNotifications(base, 0).rawQuery!!.contains("limit=1"))
        assertTrue(WatchStoreUrlPolicy.topCreators(base, 999).rawQuery!!.contains("limit=50"))
        assertTrue(WatchStoreUrlPolicy.myStats(base, 10_000).rawQuery!!.contains("days=365"))
        assertTrue(
            WatchStoreUrlPolicy.creatorWatchFaces(base, "ailife", page = 0, limit = 500)
                .rawQuery!!.let { it.contains("page=1") && it.contains("limit=100") },
        )
    }

    @Test
    fun `username goes through a character whitelist before touching a path`() {
        val base = WatchStoreUrlPolicy.normalizeBaseUrl(DEFAULT_WATCH_STORE_BASE_URL)
        // 用户名来自服务端响应，拼进路径前必须确认它改不了目标地址。
        listOf("../admin", "a/b", "ai life", "ai%2f", "ab", "").forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) {
                WatchStoreUrlPolicy.creator(base, bad)
            }
        }
        assertEquals(
            "https://jbwatchface.ai-life.xyz/api/creators/ai_life_8881",
            WatchStoreUrlPolicy.creator(base, "ai_life_8881").toASCIIString(),
        )
    }

    @Test
    fun `web pages sit on the site root, not under the api prefix`() {
        val base = WatchStoreUrlPolicy.normalizeBaseUrl(DEFAULT_WATCH_STORE_BASE_URL)
        val id = "b749ed8e-c9f1-4f82-bed6-9ea14f5252b9"
        val site = "https://jbwatchface.ai-life.xyz"

        assertEquals("$site/", WatchStoreUrlPolicy.webPage(base, WatchStoreWebPage.HOME).toASCIIString())
        assertEquals("$site/dashboard", WatchStoreUrlPolicy.webPage(base, WatchStoreWebPage.DASHBOARD).toASCIIString())
        assertEquals("$site/profile", WatchStoreUrlPolicy.webPage(base, WatchStoreWebPage.PROFILE).toASCIIString())
        assertEquals("$site/creators", WatchStoreUrlPolicy.webPage(base, WatchStoreWebPage.CREATORS).toASCIIString())
        assertEquals("$site/watchfaces/$id", WatchStoreUrlPolicy.webWatchFace(base, id).toASCIIString())
        // 单数 creator，与列表页的 /creators 不同（老师给的实例）。
        assertEquals("$site/creator/ailife", WatchStoreUrlPolicy.webCreator(base, "ailife").toASCIIString())
        // 自定义市场地址（没有 /api 后缀）也要能用。
        val custom = WatchStoreUrlPolicy.normalizeBaseUrl("http://watch.example")
        assertEquals("http://watch.example/dashboard", WatchStoreUrlPolicy.webPage(custom, WatchStoreWebPage.DASHBOARD).toASCIIString())
    }

    @Test
    fun `list items keep the counters and tags upstream already returns`() {
        val payload = """
            {"data":[{"id":"a","name":"n","screen_type":"round",
              "category":{"id":"c","name":"cn","slug":"cs"},
              "preview_url":"/api/watchfaces/a/preview","download_count":7,
              "favorite_count":3,"comment_count":2,"views":11,
              "tags":["sport","dark",""],"wm_version":"2.0","wm_protected":true,
              "layer_count":42,"created_at":"2026-01-01"}],
             "pagination":{"page":1,"limit":20,"total":1,"total_pages":1}}
        """.trimIndent()
        val item = WatchStoreJsonParser.page(payload).data.single()
        assertEquals(3L, item.favoriteCount)
        assertEquals(2L, item.commentCount)
        assertEquals(11L, item.views)
        // 空标签被丢掉，而不是变成一个空 chip。
        assertEquals(listOf("sport", "dark"), item.tags)
        assertEquals("2.0", item.wmVersion)
        assertTrue(item.wmProtected)
        assertEquals(42, item.layerCount)
    }

    @Test
    fun `an old server without the new fields still parses`() {
        // 新字段一律 optional：少一个就整页失败的话，换一台旧服务端市场就打不开了。
        val payload = """
            {"data":[{"id":"a","name":"n","screen_type":"square",
              "category":{"id":"c","name":"cn","slug":"cs"},
              "preview_url":"/p","download_count":0,"created_at":"2026-01-01"}],
             "pagination":{"page":1,"limit":20,"total":1,"total_pages":1}}
        """.trimIndent()
        val item = WatchStoreJsonParser.page(payload).data.single()
        assertEquals(0L, item.favoriteCount)
        assertEquals(emptyList<String>(), item.tags)
        assertEquals(null, item.wmVersion)
    }

    @Test
    fun `my uploads expose the review status and rejection reason`() {
        // 这是老师那条反馈的落点：上传永远返回 201，被拦只体现在这两个字段上。
        val payload = """
            {"data":[{"id":"a","name":"n","screen_type":"round",
              "category":{"id":"c","name":"cn","slug":"cs"},
              "preview_url":"/p","download_count":0,"created_at":"2026-01-01",
              "status":"rejected","rejection_reason":"preview.jpg 不是有效图片"}]}
        """.trimIndent()
        val entry = WatchStoreJsonParser.myWatchFaces(payload).single()
        assertEquals(WatchStoreReviewStatus.REJECTED, entry.status)
        assertEquals("preview.jpg 不是有效图片", entry.rejectionReason)

        // 认不出的状态不能让整条列表解析失败。
        val unknown = payload.replace("\"rejected\"", "\"quarantined\"")
        assertEquals(
            WatchStoreReviewStatus.UNKNOWN,
            WatchStoreJsonParser.myWatchFaces(unknown).single().status,
        )
    }

    @Test
    fun `account, comments, notifications and creators parse`() {
        val account = WatchStoreJsonParser.account(
            """{"id":"u1","username":"john_doe","role":"user","disabled":false,
                "created_at":"2026-04-01","description":"d","timezone":"Asia/Taipei",
                "language":"en","avatar_url":"/api/creators/john_doe/avatar"}""",
        )
        assertEquals("john_doe", account.username)
        assertEquals("/api/creators/john_doe/avatar", account.avatarUrl)

        val comments = WatchStoreJsonParser.comments(
            """{"data":[{"id":"c1","content":"hi","parent_comment_id":null,
                 "created_at":"2026-01-01","user":{"id":"u1","username":"john_doe",
                 "avatar_url":"/api/creators/john_doe/avatar"}},
                {"id":"c2","content":"re","parent_comment_id":"c1",
                 "created_at":"2026-01-02","user":{"id":"u2","username":"jane"}}]}""",
        )
        assertEquals(2, comments.size)
        assertEquals(null, comments[0].parentCommentId)
        assertEquals("/api/creators/john_doe/avatar", comments[0].authorAvatarUrl)
        assertEquals("c1", comments[1].parentCommentId)
        assertEquals("jane", comments[1].authorUsername)

        val notifications = WatchStoreJsonParser.notifications(
            """{"data":[{"id":"n1","type":"WATCHFACE_REJECTED","title":"t","message":"m",
                 "target_type":"watchface","target_id":"w1","read_at":null,
                 "created_at":"2026-01-01"},
                {"id":"n2","type":"SOMETHING_NEW","title":"t","message":"m",
                 "read_at":"2026-01-02","created_at":"2026-01-02"}],"unread":1}""",
        )
        assertEquals(1, notifications.unread)
        assertEquals(WatchStoreNotificationType.WATCHFACE_REJECTED, notifications.data[0].type)
        assertTrue(notifications.data[0].unread)
        // 没见过的类型显示成 UNKNOWN，而不是让整条列表炸掉。
        assertEquals(WatchStoreNotificationType.UNKNOWN, notifications.data[1].type)
        assertTrue(!notifications.data[1].unread)

        val creators = WatchStoreJsonParser.creators(
            """{"data":[{"id":"u1","username":"ailife","description":null,
                 "avatar_url":null,"watchface_count":12,"total_downloads":3456}]}""",
        )
        assertEquals(1, creators.size)
        assertEquals(12, creators.single().watchFaceCount)
        assertEquals(3456L, creators.single().totalDownloads)
        // null 头像给 null，而不是空串 —— 「没有头像」与「地址是空串」不是一回事。
        assertEquals(null, creators.single().avatarUrl)
    }

    @Test
    fun `feature flags default to enabled when the server says nothing`() {
        // 拿不到配置时不该把界面砍掉：真被禁会在调用时收到 403。
        val flags = WatchStoreJsonParser.featureFlags("""{"features":{}}""")
        assertTrue(flags.commentsEnabled && flags.reviewsEnabled && flags.registrationEnabled)

        val disabled = WatchStoreJsonParser.featureFlags(
            """{"features":{"comments_enabled":false,"reviews_enabled":false,
                 "registration_enabled":true}}""",
        )
        assertTrue(!disabled.commentsEnabled)
        assertTrue(!disabled.reviewsEnabled)
        assertTrue(disabled.registrationEnabled)
    }

    @Test
    fun `ratings and favourites read their scalar payloads`() {
        val summary = WatchStoreJsonParser.ratingSummary("""{"average":4.25,"count":8}""")
        assertEquals(4.25, summary.average, 0.0001)
        assertEquals(8L, summary.count)
        assertEquals(null, summary.mine)
        assertEquals(4, WatchStoreJsonParser.myRating("""{"mine":4}"""))
        assertEquals(null, WatchStoreJsonParser.myRating("""{"mine":null}"""))
        // 范围外的值当「没评过」，不要把非法评分塞进 UI。
        assertEquals(null, WatchStoreJsonParser.myRating("""{"mine":9}"""))
        assertTrue(WatchStoreJsonParser.favoriteState("""{"ok":true,"favorited":true,"favorite_count":3}"""))
        assertEquals(3L, WatchStoreJsonParser.favoriteCount("""{"favorite_count":3}"""))
    }

    @Test
    fun `rating submission response exposes the submitted rating immediately`() {
        val summary = WatchStoreJsonParser.ratingSummary(
            """{"success":true,"rating":4,"average":4.25,"count":8}""",
        )

        assertEquals(4, summary.mine)
    }

    @Test
    fun `comments truncate an oversized unpaged response instead of failing the whole section`() {
        val payload = buildString {
            append("{\"data\":[")
            repeat(501) { index ->
                if (index > 0) append(',')
                append(
                    """{"id":"$index","content":"comment-$index","parent_comment_id":null,"created_at":"2026-01-01","user":{"id":"user-$index","username":"user_$index"}}""",
                )
            }
            append("]}")
        }

        val comments = WatchStoreJsonParser.comments(payload)

        assertEquals(500, comments.size)
        assertEquals("0", comments.first().id)
        assertEquals("499", comments.last().id)
    }

    @Test
    fun `favourites tolerate the missing screen type upstream forgets to send`() {
        // 上游那条查询 select 了 screen_type 却没放进响应，必填会直接解析失败。
        val list = WatchStoreJsonParser.favorites(
            """{"data":[{"id":"a","name":"n","status":"published","preview_url":"/p",
                 "category":{"id":"c","name":"cn","slug":"cs"},
                 "download_count":5,"created_at":"2026-01-01"}]}""",
        )
        assertEquals(WatchStoreScreenType.UNKNOWN, list.single().screenType)
        assertEquals(5L, list.single().downloadCount)
    }

    @Test
    fun `profile update refuses an empty change and an unsupported language`() {
        assertThrows(IllegalArgumentException::class.java) { WatchStoreProfileUpdate() }
        assertThrows(IllegalArgumentException::class.java) {
            WatchStoreProfileUpdate(language = "kl")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WatchStoreProfileUpdate(description = "x".repeat(1_001))
        }
        assertEquals("zh-CN", WatchStoreProfileUpdate(language = "zh-CN").language)
    }

    @Test
    fun `the web host only accepts same-site urls`() {
        // C1：EXTRA_URL 来自 Intent，别的 App 也能发。站外 / 非 http(s) / 带 userinfo /
        // 畸形的一律退回站点根，绝不加载传进来的任意地址。
        val base = java.net.URI("https://jbwatchface.ai-life.xyz/api")
        val root = "https://jbwatchface.ai-life.xyz"
        fun resolve(raw: String?) =
            WatchStoreUrlPolicy.webUrlOrSiteRoot(base, raw).toASCIIString()

        assertEquals("$root/dashboard", resolve("$root/dashboard"))
        // 兜底是站点根，`siteRoot` 归一之后带一条尾斜杠。
        val fallback = "$root/"
        assertEquals(fallback, resolve(null))
        assertEquals(fallback, resolve("   "))
        assertEquals(fallback, resolve("https://evil.example/dashboard"))
        assertEquals(fallback, resolve("javascript:alert(1)"))
        // scheme 不同就不是同一个站点：HTTPS 站点上的入口不接受 http 链接。
        assertEquals(fallback, resolve("http://jbwatchface.ai-life.xyz/dashboard"))
        assertEquals(fallback, resolve("https://user@jbwatchface.ai-life.xyz/x"))
        assertEquals(fallback, resolve("://nonsense"))
        // 端口按 scheme 补齐之后才比：显式 443 与省略视为同一个站点。
        assertEquals("$root:443/x", resolve("$root:443/x"))
    }

    @Test
    fun `site image urls stay on the configured site`() {
        val base = URI("https://jbwatchface.ai-life.xyz/api")
        assertEquals(
            "https://jbwatchface.ai-life.xyz/uploads/avatar.webp",
            WatchStoreUrlPolicy.siteImageUrlOrNull(base, "/uploads/avatar.webp")?.toASCIIString(),
        )
        assertEquals(
            "https://jbwatchface.ai-life.xyz/uploads/avatar.webp",
            WatchStoreUrlPolicy.siteImageUrlOrNull(
                base,
                "https://jbwatchface.ai-life.xyz/uploads/avatar.webp",
            )?.toASCIIString(),
        )
        assertNull(
            WatchStoreUrlPolicy.siteImageUrlOrNull(base, "https://evil.example/avatar.webp"),
        )
        assertNull(
            WatchStoreUrlPolicy.siteImageUrlOrNull(
                base,
                "https://user@jbwatchface.ai-life.xyz/avatar.webp",
            ),
        )
        assertNull(
            WatchStoreUrlPolicy.siteImageUrlOrNull(
                base,
                "https://jbwatchface.ai-life.xyz/avatar.webp#fragment",
            ),
        )

        val customBase = URI("https://market.example/community/api")
        assertEquals(
            "https://market.example/community/uploads/avatar.webp",
            WatchStoreUrlPolicy.siteImageUrlOrNull(customBase, "/community/uploads/avatar.webp")
                ?.toASCIIString(),
        )
    }

    @Test
    fun `the web host recognises watch face download links`() {
        // C3：认出来的交回商店走既有安装流程；认不出来的一律 null（宿主提示而不是瞎装）。
        val base = java.net.URI("https://jbwatchface.ai-life.xyz/api")
        val id = "11111111-2222-3333-4444-555555555555"
        assertEquals(
            id,
            WatchStoreUrlPolicy.downloadWatchFaceIdOrNull(
                base,
                "https://jbwatchface.ai-life.xyz/api/watchfaces/$id/download",
            ),
        )
        // 站点根下的同形链接（没有 /api 前缀）也算。
        assertEquals(
            id,
            WatchStoreUrlPolicy.downloadWatchFaceIdOrNull(
                base,
                "https://jbwatchface.ai-life.xyz/watchfaces/$id/download",
            ),
        )
        listOf(
            null,
            "",
            "https://evil.example/watchfaces/$id/download",
            "https://jbwatchface.ai-life.xyz/watchfaces/$id",
            "https://jbwatchface.ai-life.xyz/watchfaces/not-a-uuid/download",
            "https://jbwatchface.ai-life.xyz/$id/watchfaces/download",
        ).forEach { raw ->
            assertNull(raw, WatchStoreUrlPolicy.downloadWatchFaceIdOrNull(base, raw))
        }
    }

    @Test
    fun `the web zoom percent stays inside the twenty five to two fifty band`() {
        // H6：这一项要限范围 —— WebView 缩放超出 25–250 会直接排版崩坏。
        assertEquals(25, normalizeWebZoomPercent(0))
        assertEquals(25, normalizeWebZoomPercent(-100))
        assertEquals(250, normalizeWebZoomPercent(1_000))
        assertEquals(100, normalizeWebZoomPercent(100))
        assertEquals(137, normalizeWebZoomPercent(137))
    }
}
