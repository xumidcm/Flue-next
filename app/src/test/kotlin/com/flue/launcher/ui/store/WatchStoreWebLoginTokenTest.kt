package com.flue.launcher.ui.store

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WatchStoreWebLoginTokenTest {

    @Test
    fun cookieJwtIsUsedWhenLocalStorageHasNoToken() {
        val token = jwtWithExpiry(
            expSeconds = 1_700_003_600L,
            username = "cookie-user",
        )

        assertEquals(
            listOf(token),
            watchStoreWebLoginTokenCandidates(
                localStorageRaw = "null",
                cookieHeader = "session=opaque; token=$token; Path=/",
            ),
        )
    }

    @Test
    fun validCookieJwtIsParsedWithItsRealExpiryAndUsername() {
        val token = jwtWithExpiry(
            expSeconds = 1_700_003_600L,
            username = "cookie-user",
        )

        val identity = parseWatchStoreWebLoginToken(
            token = token,
            nowEpochMs = 1_700_000_000_000L,
        )

        assertEquals(token, identity.token)
        assertEquals("cookie-user", identity.username)
        assertEquals(1_700_003_600_000L, identity.expiresAtEpochMs)
    }

    @Test
    fun expiredOrIncompleteCookieJwtIsRejected() {
        val expired = jwtWithExpiry(1_699_999_999L, "cookie-user")
        val missingUsername = jwtWithExpiry(1_700_003_600L, "")

        assertTrue(runCatching {
            parseWatchStoreWebLoginToken(expired, 1_700_000_000_000L)
        }.isFailure)
        assertTrue(runCatching {
            parseWatchStoreWebLoginToken(missingUsername, 1_700_000_000_000L)
        }.isFailure)
    }

    private fun jwtWithExpiry(expSeconds: Long, username: String): String {
        val flags = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        val header = Base64.encodeToString("{}".toByteArray(), flags)
        val payload = Base64.encodeToString(
            "{\"exp\":$expSeconds,\"username\":\"$username\"}".toByteArray(),
            flags,
        )
        return "$header.$payload.signature"
    }
}
