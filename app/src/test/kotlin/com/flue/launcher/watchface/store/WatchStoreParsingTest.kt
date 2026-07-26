package com.flue.launcher.watchface.store

import java.net.URI
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE)
class WatchStoreParsingTest {
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
    fun `url policy rejects insecure bases and redirects`() {
        assertThrows(IllegalArgumentException::class.java) {
            WatchStoreUrlPolicy.normalizeBaseUrl("http://example.com/api")
        }
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
}
