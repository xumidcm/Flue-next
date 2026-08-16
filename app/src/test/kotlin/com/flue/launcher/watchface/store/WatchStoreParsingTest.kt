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
    fun `upload limit matches current community store contract`() {
        assertEquals(20L * 1024L * 1024L, HttpWatchStoreApi.MAX_UPLOAD_BYTES)
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
}
