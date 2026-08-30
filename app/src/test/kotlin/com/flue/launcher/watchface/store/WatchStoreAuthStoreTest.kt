package com.flue.launcher.watchface.store

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WatchStoreAuthStoreTest {

    @Test
    fun writeUsesTheJwtExpiryInsteadOfAFixedSevenDayLifetime() {
        val nowSeconds = 1_700_000_000L
        val token = jwtWithExpiry(nowSeconds + 3_600L)
        val store = WatchStoreAuthStore(
            RuntimeEnvironment.getApplication(),
        )

        val session = store.write(
            token = token,
            username = "tester",
            loginAtEpochMs = nowSeconds * 1_000L,
        )

        assertEquals((nowSeconds + 3_600L) * 1_000L, session.expiresAtEpochMs)
        assertEquals(session, store.read(nowSeconds * 1_000L + 3_599_000L))
        store.clear()
    }

    private fun jwtWithExpiry(exp: Long): String {
        val header = Base64.encodeToString("{}".toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val payload = Base64.encodeToString(
            "{\"exp\":$exp,\"username\":\"tester\"}".toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return "$header.$payload.signature"
    }
}
